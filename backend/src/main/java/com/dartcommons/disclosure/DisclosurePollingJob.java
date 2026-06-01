package com.dartcommons.disclosure;

import com.dartcommons.disclosure.services.DisclosureCollectionService;
import com.dartcommons.infrastructure.dart.DartApiException;
import com.dartcommons.infrastructure.dart.DartClient;
import com.dartcommons.infrastructure.dart.dto.DartListResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/*
 * [목적] 1분 주기로 DART OpenAPI를 폴링해 신규 공시를 수집하는 스케줄러.
 *       콜드스타트는 당일, 이후 마지막 성공 폴링 날짜 기준 윈도우로 실행.
 * [이유] CLAUDE.md §4 "신규 공시는 @Scheduled 1분 폴링으로 감지" 요구사항 구현.
 *       비즈니스 로직은 DisclosureCollectionService에 위임 — 잡은 트리거 역할만.
 * [사이드 임팩트] DART API 장애 시 예외를 catch 후 로깅만 하고 잡을 중단하지 않음.
 *               lastPolledDate가 인메모리라 애플리케이션 재시작 시 오늘부터 재폴링(rcept_no 멱등 보호).
 *               멀티 인스턴스 배포 시 ShedLock 등 분산 락 필요(현재 MVP 단일 인스턴스 가정).
 * [수정 시 고려사항] fixedDelay(이전 완료 후 1분)가 fixedRate(절대 1분)보다 안전 — DART 장시간 호출 시 중복 방지.
 *                  lastPolledDate를 DB/Redis로 영속화하면 재시작 시 누락 없이 이어서 폴링 가능.
 *                  pblntf_ty 필터를 DartClient에 추가하면 불필요한 공시 수신 감소.
 */
@Component
@RequiredArgsConstructor
public class DisclosurePollingJob {

    private static final Logger log = LoggerFactory.getLogger(DisclosurePollingJob.class);

    private final DartClient dartClient;
    private final DisclosureCollectionService collectionService;

    /** 마지막 성공 폴링 날짜. null이면 콜드스타트(오늘로 초기화). */
    private final AtomicReference<LocalDate> lastPolledDate = new AtomicReference<>(null);

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void poll() {
        LocalDate today = LocalDate.now();
        LocalDate from = lastPolledDate.get() != null ? lastPolledDate.get() : today;

        log.debug("DART polling start: bgnDe={}, endDe={}", from, today);

        try {
            List<DartListResponse.Item> items = dartClient.fetchList(from, today);
            int savedCount = collectionService.collect(items);

            lastPolledDate.set(today);
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
}
