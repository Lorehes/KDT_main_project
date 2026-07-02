package com.dartcommons.stocks.services;

import com.dartcommons.infrastructure.krx.KrxClient;
import com.dartcommons.infrastructure.krx.KrxClient.StockCloseInfo;
import com.dartcommons.stocks.entities.PriceBackfillJob;
import com.dartcommons.stocks.entities.Stock;
import com.dartcommons.stocks.repositories.PriceBackfillJobRepository;
import com.dartcommons.stocks.repositories.StockPriceRepository;
import com.dartcommons.stocks.repositories.StockRepository;
import com.dartcommons.shared.util.SecretMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/*
 * [목적] KRX 과거 3년치 종가를 커버 종목(코스피200+코스닥150)에 한해 stock_prices에 백필하는 비동기 관리자 잡.
 *       예측 차트(#8/#9)가 과거 유사 공시의 D+1~D+5 반응을 산출할 데이터 확보(krx-price-timeseries Wave B).
 * [이유] Wave A 일배치는 오늘부터만 축적 → 과거 공시 반응 계산 불가. 3년 소급 수집 필요.
 *       V26 EmbeddingBackfillService 패턴 적용(AtomicBoolean + 잡 테이블 진행률/재개 + 안전망).
 *       커서가 날짜(역순: 최근→과거)인 점만 상이 — 최근 데이터부터 확보해 중단되어도 유용.
 * [사이드 임팩트] priceBackfillExecutor(core1/max1) 점유 ~30분. KRX 날짜별 반복 호출 — rate limit 시 각 날짜 폴백(GitHub cache).
 *               커버 종목 필터 필수 — fetchClosePricesForDate는 전종목(~2800) 반환하나 stock_prices FK가 stocks(~341)만 참조.
 *               필터 없이 upsert 시 FK 위반. StockPriceRepository.upsertPrice는 ON CONFLICT DO NOTHING(멱등).
 *               비거래일(공휴일)은 fetchClosePricesForDate가 빈 Map → failedDate로 카운트(정상, 오류 아님).
 * [수정 시 고려사항] BACKFILL_YEARS(3)·EARLY_ABORT_THRESHOLD(20) 상수 조정 시 여기만.
 *                  안전망: 최근 평일 20일 시도 후 성공 0건이면 KRX/GitHub 미가동 판정 → throw(대부분 거래일이라 0=소스 장애).
 *                  단일 인스턴스 배포 불변식 유지 — 수평 확장 전 분산 락 Spec 선행.
 *                  수정주가 미보정(raw) — 분할·합병 구간 반응 왜곡 가능(Spec 확정: 초기 raw).
 */
@Service
public class PriceBackfillService {

    private static final Logger log = LoggerFactory.getLogger(PriceBackfillService.class);
    private static final int BACKFILL_YEARS = 3;
    /** 안전망 — 최근 평일 이만큼 시도 후 성공(비어있지 않은 응답) 0건이면 KRX/GitHub 장애로 판정하고 throw. */
    private static final int EARLY_ABORT_THRESHOLD = 20;
    /** 진행률 flush 주기(평일) — 매 날짜 커밋 대신 배치 커밋. 크래시 시 최대 이만큼 재처리(멱등). */
    private static final int PROGRESS_FLUSH_EVERY = 20;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final PriceBackfillJobStateService stateService;
    private final PriceBackfillJobRepository jobRepository;
    private final StockRepository stockRepository;
    private final StockPriceRepository stockPriceRepository;
    private final KrxClient krxClient;
    private final TaskExecutor priceBackfillExecutor;

    public PriceBackfillService(PriceBackfillJobStateService stateService,
                                PriceBackfillJobRepository jobRepository,
                                StockRepository stockRepository,
                                StockPriceRepository stockPriceRepository,
                                KrxClient krxClient,
                                @Qualifier("priceBackfillExecutor") TaskExecutor priceBackfillExecutor) {
        this.stateService = stateService;
        this.jobRepository = jobRepository;
        this.stockRepository = stockRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.krxClient = krxClient;
        this.priceBackfillExecutor = priceBackfillExecutor;
    }

    /** CAS 원자 확보 → 잡 생성 → 비동기 실행. 이미 실행 중이면 Optional.empty()(호출자 409). */
    public Optional<PriceBackfillJob> createAndStartAsync() {
        if (!running.compareAndSet(false, true)) return Optional.empty();
        try {
            PriceBackfillJob job = createJob();
            UUID jobId = job.getJobId();
            priceBackfillExecutor.execute(() -> {
                try {
                    doBackfill(jobId);
                } catch (Exception e) {
                    // errorMessage는 /admin 응답에 노출 — DB/커넥션 단편 유출 방지 위해 마스킹(방어적, KRX 메시지는 이미 소스에서 마스킹됨)
                    stateService.failJob(jobId, SecretMasker.mask(e.getMessage()));
                    log.error("PriceBackfillService: doBackfill failed jobId={}", jobId, e);
                } finally {
                    running.set(false);
                }
            });
            return Optional.of(job);
        } catch (Exception e) {
            running.set(false);
            throw e;
        }
    }

    /**
     * 백필 핵심 루프 — 어제부터 3년 전까지 평일 역순 반복.
     * 각 날짜: fetchClosePricesForDate → 커버 종목만 upsert(FK 안전) → 진행률 기록.
     * 재시작 복구: stale RUNNING 잡의 lastProcessedDate 이전부터 재개.
     */
    private void doBackfill(UUID jobId) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate lowerBound = today.minusYears(BACKFILL_YEARS);
        LocalDate yesterday = today.minusDays(1);

        LocalDate resumeFrom = resolveResumePoint(jobId);
        LocalDate cursor = (resumeFrom != null) ? resumeFrom.minusDays(1) : yesterday;

        // targeted = 이번 실행이 처리할 범위[lowerBound, cursor]의 평일 수. 재개 시 남은 구간만 반영해 진행률 정합.
        int targeted = countWeekdays(lowerBound, cursor);
        stateService.startJob(jobId, targeted);

        // 커버 종목 코드 집합 — stock_prices FK가 stocks(커버 종목)만 참조하므로 이 집합으로 필터 필수.
        Set<String> coveredCodes = stockRepository.findAll().stream()
                .map(Stock::getStockCode).collect(Collectors.toCollection(HashSet::new));

        log.info("PriceBackfillService: start jobId={} targeted={}(평일) covered={} resumeFrom={}",
                jobId, targeted, coveredCodes.size(), cursor);

        int attempts = 0;         // 시도한 평일 수
        int datesOk = 0;          // 데이터 있던 날짜 수(누적)
        int datesEmpty = 0;       // 비어있던(비거래일·실패) 날짜 수(누적)
        int consecutiveEmpty = 0; // 연속 빈 응답 평일 수 — 소스 장애 감지(장 연휴 최대 ~6일 << 20)
        long totalRows = 0;
        // 진행률 flush 배치 — 매 날짜 REQUIRES_NEW 커밋 대신 PROGRESS_FLUSH_EVERY 평일마다 1회(커밋 20배 절감).
        int pendingOk = 0, pendingEmpty = 0;

        while (!cursor.isBefore(lowerBound)) {
            if (isWeekday(cursor)) {
                attempts++;
                Map<String, StockCloseInfo> prices = krxClient.fetchClosePricesForDate(cursor);

                if (prices.isEmpty()) {
                    datesEmpty++; pendingEmpty++; consecutiveEmpty++;
                } else {
                    int inserted = 0;
                    for (Map.Entry<String, StockCloseInfo> e : prices.entrySet()) {
                        if (!coveredCodes.contains(e.getKey())) continue;  // FK 안전 — 커버 종목만
                        stockPriceRepository.upsertPrice(e.getKey(), e.getValue().priceAsof(), e.getValue().closePrice());
                        inserted++;
                    }
                    datesOk++; pendingOk++; consecutiveEmpty = 0;
                    totalRows += inserted;
                }

                // 20 평일마다 진행률 flush(재개 포인트=cursor) — 크래시 시 최대 20일 재처리(멱등이라 안전)
                if (attempts % PROGRESS_FLUSH_EVERY == 0) {
                    stateService.recordProgress(jobId, pendingOk, pendingEmpty, cursor);
                    pendingOk = 0; pendingEmpty = 0;
                    log.info("PriceBackfillService: progress attempts={} datesOk={} datesEmpty={} rows={} cursor={}",
                            attempts, datesOk, datesEmpty, totalRows, cursor);
                }

                // 안전망 — 연속 20 평일 빈 응답이면 KRX/GitHub 장애로 판정하고 throw.
                // 시작 시 장애(첫 20일 0건)뿐 아니라 중간 장애(정상 진행 중 소스 다운)도 포착 —
                // 장 연휴 최대 연속 평일(~6일) << 20이라 정상 휴장은 오탐 안 함.
                if (consecutiveEmpty >= EARLY_ABORT_THRESHOLD) {
                    throw new IllegalStateException(
                            "PriceBackfill 조기 중단: 연속 평일 " + EARLY_ABORT_THRESHOLD +
                            "일 종가 0건(cursor=" + cursor + "). KRX/GitHub cache 가동 여부 확인 필요.");
                }
            }
            cursor = cursor.minusDays(1);
        }

        // 남은 진행률 flush(마지막 부분 배치)
        if (pendingOk > 0 || pendingEmpty > 0) {
            stateService.recordProgress(jobId, pendingOk, pendingEmpty, lowerBound);
        }
        stateService.succeedJob(jobId);
        log.info("PriceBackfillService: done jobId={} datesOk={} datesEmpty={} totalRows={}",
                jobId, datesOk, datesEmpty, totalRows);
    }

    /** 재시작 재개 포인트 — stale RUNNING 잡이 있으면 lastProcessedDate 반환(그 이전부터 재개), 없으면 null. */
    private LocalDate resolveResumePoint(UUID currentJobId) {
        Optional<PriceBackfillJob> stale = jobRepository
                .findFirstByStatusOrderByCreatedAtDesc(PriceBackfillJob.Status.RUNNING);
        if (stale.isPresent() && !stale.get().getJobId().equals(currentJobId)) {
            LocalDate resumeDate = stale.get().getLastProcessedDate();
            log.info("PriceBackfillService: stale RUNNING job(jobId={}) resuming from before {}",
                    stale.get().getJobId(), resumeDate);
            stateService.failJob(stale.get().getJobId(), "Superseded by new job " + currentJobId + " on restart");
            return resumeDate;
        }
        return null;
    }

    private static boolean isWeekday(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** from..to(inclusive) 사이 평일 수 — targeted 스냅샷용. */
    private static int countWeekdays(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWeekday(d)) count++;
        }
        return count;
    }

    public boolean isRunning() { return running.get(); }

    @Transactional(readOnly = true)
    public Optional<PriceBackfillJob> findByJobId(UUID jobId) {
        return jobRepository.findByJobId(jobId);
    }

    @Transactional
    public PriceBackfillJob createJob() {
        PriceBackfillJob job = jobRepository.save(PriceBackfillJob.create());
        log.info("PriceBackfillJob created: jobId={}", job.getJobId());
        return job;
    }
}
