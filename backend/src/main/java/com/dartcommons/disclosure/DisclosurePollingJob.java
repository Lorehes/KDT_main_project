package com.dartcommons.disclosure;

import com.dartcommons.disclosure.dto.RawDisclosureItem;
import com.dartcommons.disclosure.services.DisclosureCollectionService;
import com.dartcommons.infrastructure.dart.DartApiException;
import com.dartcommons.infrastructure.dart.DartClient;
import com.dartcommons.shared.config.SystemConfig;
import com.dartcommons.shared.config.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/*
 * [목적] 1분 주기로 DART OpenAPI를 폴링해 신규 공시를 수집하는 스케줄러.
 *       마지막 성공 폴링 날짜를 system_configs(V11)에 영속화 — 재기동 시 누락 회복.
 * [이유] CLAUDE.md §4 "신규 공시는 @Scheduled 1분 폴링으로 감지" 요구사항 구현.
 *       deferred MEDIUM 해결: 인메모리 AtomicReference → DB 영속화 + 재기동 시 자동 복원.
 *       비즈니스 로직은 DisclosureCollectionService에 위임 — 잡은 트리거 + 상태 관리만.
 * [사이드 임팩트] DART API 장애 시 예외를 catch 후 로깅만 하고 잡을 중단하지 않음.
 *               lastPolledDate 영속화 실패(DB 장애) 시도 폴링 자체는 계속(상태 갱신만 누락).
 *               멀티 인스턴스 배포 시 ShedLock + system_configs row-lock 동시 사용 권장.
 *               콜드스타트(처음 부팅, 키 부재) 시 system_configs에 키 없음 → 오늘부터 시작.
 * [수정 시 고려사항] fixedDelay(이전 완료 후 1분)가 fixedRate(절대 1분)보다 안전 — DART 장시간 호출 시 중복 방지.
 *                  pblntf_ty 필터를 DartClient에 추가하면 불필요한 공시 수신 감소.
 *                  config_key 변경 시 마이그레이션(이전 값 복사) 필요.
 */
@Component
@RequiredArgsConstructor
public class DisclosurePollingJob {

    private static final Logger log = LoggerFactory.getLogger(DisclosurePollingJob.class);
    private static final String CONFIG_KEY_LAST_POLLED = "disclosure.lastPolledDate";

    private final DartClient dartClient;
    private final DisclosureCollectionService collectionService;
    private final SystemConfigRepository systemConfigRepository;

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void poll() {
        LocalDate today = LocalDate.now();
        LocalDate from = loadLastPolledDate().orElse(today);

        log.debug("DART polling start: bgnDe={}, endDe={}", from, today);

        try {
            List<RawDisclosureItem> items = dartClient.fetchList(from, today);
            int savedCount = collectionService.collect(items);

            saveLastPolledDate(today);
            log.info("DART polling done: fetched={}, saved={}, window={}~{}", items.size(), savedCount, from, today);

        } catch (DartApiException e) {
            if (e.isCritical()) {
                log.error("DART API critical error — check key/system: {}", e.getMessage());
            } else {
                log.warn("DART API non-critical error, will retry next cycle: {}", e.getMessage());
            }
            // lastPolledDate 갱신 안 함 → 다음 폴링에서 동일 윈도우 재시도
        } catch (Exception e) {
            log.error("Unexpected error during DART polling", e);
        }
    }

    /**
     * system_configs에서 마지막 폴링 종료일자 복원. 키 부재 시 빈 Optional → 호출자가 콜드스타트 정책 결정.
     */
    private java.util.Optional<LocalDate> loadLastPolledDate() {
        try {
            return systemConfigRepository.findByKey(CONFIG_KEY_LAST_POLLED)
                    .map(SystemConfig::getValue)
                    .map(LocalDate::parse);
        } catch (Exception e) {
            log.warn("lastPolledDate 로드 실패 — 콜드스타트로 진행: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    @Transactional
    public void saveLastPolledDate(LocalDate date) {
        SystemConfig config = systemConfigRepository.findByKey(CONFIG_KEY_LAST_POLLED)
                .orElseGet(() -> SystemConfig.of(CONFIG_KEY_LAST_POLLED, date.toString()));
        config.update(date.toString());
        systemConfigRepository.save(config);
    }
}
