package com.dartcommons.disclosure.services;

import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.dart.DartClient;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
 * [목적] 임의 날짜 범위(예: 3년치)의 DART 공시를 백필(backfill)하는 일회성/관리자용 서비스.
 *       정상 폴링(DisclosurePollingJob)과 코드 경로 분리 — 대량 적재 최적화 + 진행률 로깅.
 * [이유] 통합기획서 §16 Phase 1 데이터 토대 확보: 운영 환경 stocks 시드 후 과거 공시 일괄 적재.
 *       DART list.json은 단일 호출 윈도우 제한이 있어 큰 범위는 청크 분할 필수.
 *       대량 INSERT(수십~수백만 행 가능)는 건별 save 대신 saveAll + 청크 트랜잭션으로 성능 확보.
 * [사이드 임팩트] 매우 대용량 호출 가능 — DART rate limit(시간당 1,000회/일 20,000회) 주의.
 *               이벤트(DisclosureCollectedEvent)를 정상 경로와 동일하게 발행하면 analysis 도메인이
 *               백필 분량 전체를 큐잉할 위험. **백필 경로는 이벤트 미발행** — 분석은 별도 배치로 트리거.
 *               멱등(rcept_no UNIQUE)이라 중간 실패 재실행 안전.
 * [수정 시 고려사항] 청크 크기(WINDOW_DAYS): 너무 크면 DART 한도 초과, 너무 작으면 호출 수 폭증.
 *                  90일이 경험적 균형(통상 DART list.json 제한 범위 내).
 *                  CHUNK_SIZE(saveAll 청크): hibernate.jdbc.batch_size와 정렬 권장.
 *                  진행률 로그는 INFO — 대량 작업 모니터링 용.
 *                  실행 권한은 BackfillController에서 인증/권한 가드 필수(운영자 전용).
 */
@Service
@RequiredArgsConstructor
public class DisclosureBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DisclosureBackfillService.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** DART list.json 단일 호출 윈도우 — 90일이 통상 안전 범위. */
    private static final int WINDOW_DAYS = 90;

    /** saveAll 청크 크기 — hibernate.jdbc.batch_size 권장값과 정렬. */
    private static final int CHUNK_SIZE = 500;

    private final DartClient dartClient;
    private final DisclosureRepository disclosureRepository;
    private final StockRepository stockRepository;
    private final DisclosureTypeClassifier typeClassifier;
    private final ApplicationEventPublisher eventPublisher;

    @Lazy
    @Autowired
    private DisclosureBackfillService self;

    /**
     * 임의 날짜 범위를 90일 청크로 분할해 순차 백필.
     *
     * @param from 시작일 (inclusive)
     * @param to 종료일 (inclusive)
     * @param emitEvents 분석 도메인 이벤트 발행 여부 — 백필은 일반적으로 false 권장
     * @return 전체 저장된 공시 건수
     */
    public BackfillResult backfill(LocalDate from, LocalDate to, boolean emitEvents) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from(" + from + ") must be <= to(" + to + ")");
        }
        log.info("Backfill start: from={}, to={}, emitEvents={}", from, to, emitEvents);

        Set<String> coveredCodes = stockRepository.findAllStockCodes();
        if (coveredCodes.isEmpty()) {
            log.warn("Backfill: stocks 마스터 비어있음 — 모든 공시가 skip됩니다. V10 시드 적용 후 재실행 권장.");
        }

        int totalSaved = 0;
        int totalFetched = 0;
        int chunks = 0;
        LocalDate windowStart = from;

        while (!windowStart.isAfter(to)) {
            LocalDate windowEnd = windowStart.plusDays(WINDOW_DAYS - 1L);
            if (windowEnd.isAfter(to)) windowEnd = to;

            chunks++;
            try {
                List<DartListResponse.Item> items = dartClient.fetchList(windowStart, windowEnd);
                totalFetched += items.size();

                int saved = self.persistChunked(items, coveredCodes, emitEvents);
                totalSaved += saved;
                log.info("Backfill window {}~{}: fetched={}, saved={} (cumulative saved={})",
                        windowStart, windowEnd, items.size(), saved, totalSaved);
            } catch (Exception e) {
                log.error("Backfill window {}~{} failed — 계속 진행", windowStart, windowEnd, e);
            }

            windowStart = windowEnd.plusDays(1);
        }

        log.info("Backfill done: chunks={}, fetched={}, saved={}", chunks, totalFetched, totalSaved);
        return new BackfillResult(from, to, chunks, totalFetched, totalSaved);
    }

    /**
     * 한 윈도우 분량의 공시 항목을 커버 필터 + 멱등 체크 후 청크 단위 saveAll로 적재.
     * 각 청크는 독립 트랜잭션 — 한 청크 실패가 전체를 롤백하지 않음.
     */
    @Transactional
    public int persistChunked(List<DartListResponse.Item> items, Set<String> coveredCodes, boolean emitEvents) {
        if (items.isEmpty()) return 0;

        List<Disclosure> buffer = new ArrayList<>(CHUNK_SIZE);
        Set<String> seenInBatch = new HashSet<>();
        int saved = 0;

        for (DartListResponse.Item item : items) {
            String stockCode = item.stockCodeOrNull();
            if (stockCode == null || !coveredCodes.contains(stockCode)) continue;
            if (!seenInBatch.add(item.rceptNo())) continue;  // 배치 내 중복 skip
            if (disclosureRepository.existsByRceptNo(item.rceptNo())) continue;  // 기존 적재분 skip

            buffer.add(buildEntity(item, stockCode));
            if (buffer.size() >= CHUNK_SIZE) {
                saved += flushBuffer(buffer, emitEvents);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) saved += flushBuffer(buffer, emitEvents);
        return saved;
    }

    private int flushBuffer(List<Disclosure> buffer, boolean emitEvents) {
        try {
            List<Disclosure> persisted = disclosureRepository.saveAll(buffer);
            if (emitEvents) {
                for (Disclosure d : persisted) {
                    eventPublisher.publishEvent(new DisclosureCollectedEvent(d.getId()));
                }
            }
            return persisted.size();
        } catch (DataIntegrityViolationException e) {
            // 청크 내 race condition — 청크 통째로 무시(개별 retry는 다음 백필 호출에 위임)
            log.warn("Backfill chunk save failed (likely concurrent insert), skipping {} items: {}",
                    buffer.size(), e.getMessage());
            return 0;
        }
    }

    private Disclosure buildEntity(DartListResponse.Item item, String stockCode) {
        return Disclosure.builder()
                .rceptNo(item.rceptNo())
                .corpCode(item.corpCode())
                .stockCode(stockCode)
                .corpName(item.corpName())
                .reportNm(item.reportNm())
                .rceptDt(LocalDate.parse(item.rceptDt(), YYYYMMDD))
                .disclosureType(typeClassifier.classify(item.reportNm()))
                .build();
    }

    public record BackfillResult(LocalDate from, LocalDate to, int chunks, int fetched, int saved) {
    }
}
