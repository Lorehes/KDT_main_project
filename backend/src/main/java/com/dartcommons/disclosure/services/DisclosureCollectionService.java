package com.dartcommons.disclosure.services;

import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.dart.dto.DartListResponse;
import com.dartcommons.shared.event.DisclosureCollectedEvent;
import com.dartcommons.stocks.repositories.StockRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/*
 * [목적] DART에서 수집한 공시 항목을 추출→분류→커버필터→멱등적재→이벤트발행하는 Stage 1 오케스트레이터.
 * [이유] DisclosurePollingJob(스케줄러)과 비즈니스 로직을 분리해 단위 테스트 가능하게 유지.
 *       각 항목을 독립 try-catch로 처리해 한 건 실패가 나머지 배치를 중단하지 않도록 보장.
 *       커버 필터는 배치당 1회 StockRepository.findAllStockCodes()로 Set을 로드해 N+1 회피
 *       (이전 JdbcTemplate native query 방식은 항목당 1쿼리 → stocks-master-seed Spec 카드 #10에서 회귀).
 * [사이드 임팩트] DART 원본(corp_name·report_nm·수치·날짜)을 DB에 그대로 저장 — 변형 금지(CLAUDE.md §4).
 *               DisclosureCollectedEvent 발행 → analysis 도메인 리스너가 Stage 2 트리거(미구현 시 무해 무시).
 *               커버 종목 Set은 collect() 호출 단위로만 유효 — 분기 동기화 잡이 stocks를 갱신해도
 *               다음 폴링부터 반영(현재 폴링 배치 중에는 갱신 미반영, 1분 폴링 주기로 허용 범위).
 * [수정 시 고려사항] 약 350행 마스터라 Set 메모리 부담 무시 가능 — 커버리지 확대 시 Caffeine 캐시 검토.
 *                  대량 수집 시 saveAll 배치 최적화 검토.
 *                  self 필드(@Lazy 자기 참조)는 @Transactional 프록시를 경유하기 위한 패턴 —
 *                  collect()가 this.collectSingle()을 직접 호출하면 AOP 프록시를 우회해 트랜잭션이 무력화됨.
 */
@Service
@RequiredArgsConstructor
public class DisclosureCollectionService {

    private static final Logger log = LoggerFactory.getLogger(DisclosureCollectionService.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DisclosureRepository disclosureRepository;
    private final StockRepository stockRepository;
    private final DisclosureTypeClassifier typeClassifier;
    private final ApplicationEventPublisher eventPublisher;

    // @Transactional 프록시 경유를 위한 자기 참조 — @Lazy로 순환 의존성 방지
    @Lazy
    @Autowired
    private DisclosureCollectionService self;

    /**
     * DART 공시 항목 리스트를 수신해 적재 가능한 항목만 처리한다.
     * 배치 진입 시 커버 종목 코드 Set을 1회 로드해 항목별 N+1 쿼리를 회피한다.
     *
     * @return 실제로 저장된 건수
     */
    public int collect(List<DartListResponse.Item> items) {
        // 커버 종목 코드 1회 로드 — 배치 처리 중 stocks 변경은 다음 폴링부터 반영
        Set<String> coveredCodes = stockRepository.findAllStockCodes();

        int savedCount = 0;
        for (DartListResponse.Item item : items) {
            try {
                if (self.collectSingle(item, coveredCodes)) savedCount++;
            } catch (Exception e) {
                log.error("Failed to collect disclosure rceptNo={}", item.rceptNo(), e);
            }
        }
        return savedCount;
    }

    /**
     * 공시 항목 1건을 처리한다. 멱등(중복 skip), 커버 필터, 저장, 이벤트 발행.
     *
     * @param coveredCodes collect() 진입 시 로드한 커버 종목 Set
     * @return 저장 성공 시 true, skip 시 false
     */
    @Transactional
    public boolean collectSingle(DartListResponse.Item item, Set<String> coveredCodes) {
        // 1. rcept_no 멱등 체크 — 이미 존재하면 skip (CLAUDE.md §4)
        if (disclosureRepository.existsByRceptNo(item.rceptNo())) {
            log.debug("Skip duplicate disclosure rceptNo={}", item.rceptNo());
            return false;
        }

        // 2. 커버 종목 필터 — stocks 마스터에 없는 종목은 skip (통합기획서 §3.1)
        String stockCode = item.stockCodeOrNull();
        if (stockCode == null || !coveredCodes.contains(stockCode)) {
            log.debug("Skip uncovered disclosure rceptNo={}, stockCode={}", item.rceptNo(), stockCode);
            return false;
        }

        // 3. 유형 룰 분류
        String disclosureType = typeClassifier.classify(item.reportNm());

        // 4. 엔티티 빌드 — DART 원본 필드 그대로 저장 (LLM 변형 금지)
        Disclosure disclosure = Disclosure.builder()
                .rceptNo(item.rceptNo())
                .corpCode(item.corpCode())
                .stockCode(stockCode)
                .corpName(item.corpName())
                .reportNm(item.reportNm())
                .rceptDt(LocalDate.parse(item.rceptDt(), YYYYMMDD))
                .disclosureType(disclosureType)
                .build();

        try {
            disclosure = disclosureRepository.save(disclosure);
        } catch (DataIntegrityViolationException e) {
            // race condition: 동시 폴링 인스턴스가 동일 rcept_no를 INSERT한 경우 → skip
            log.warn("Concurrent insert detected, skipping rceptNo={}", item.rceptNo());
            return false;
        }

        // 5. 이벤트 발행 — @TransactionalEventListener(AFTER_COMMIT)로 analysis 도메인이 수신
        eventPublisher.publishEvent(new DisclosureCollectedEvent(disclosure.getId()));
        log.info("Collected disclosure rceptNo={}, type={}, corp={}", item.rceptNo(), disclosureType, item.corpName());

        return true;
    }
}
