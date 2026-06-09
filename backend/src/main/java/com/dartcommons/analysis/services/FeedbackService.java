package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.FeedbackRequest;
import com.dartcommons.analysis.entities.FeedbackEntity;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.analysis.repositories.FeedbackRepository;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.user.repositories.PortfolioRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * [목적] 분석 피드백 저장 서비스 — 신규 투표(INSERT) + 재투표(UPDATE) upsert 패턴.
 *       + 포트폴리오 소유권 검증(IDOR 방어) + userId 시간당 rate-limit(분석 은폐 공격 방어).
 * [이유] uq_feedbacks_user_analysis UNIQUE 제약으로 DB INSERT 충돌 대신 application 계층에서 UPDATE 경로를 명시적으로 처리.
 *       analysis_id → disclosureId → stockCode → portfolio(userId) 체인으로 소유권 검증 — 타인 분석 피드백 투표 차단.
 *       INACCURATE 대량 자동화 투표(분석 은폐 공격) 차단을 위해 Caffeine 인메모리 rate-limit 적용.
 *       TOCTOU: 동시 INSERT 충돌 시 DataIntegrityViolationException이 PostgreSQL 트랜잭션을 ABORTED 상태로 전환
 *       → 같은 트랜잭션 내 후속 쿼리 실패. 해결책: tryInsertFeedback()을 REQUIRES_NEW로 격리해 충돌 트랜잭션만 롤백.
 * [사이드 임팩트] DisclosureRepository·PortfolioRepository — analysis 도메인의 disclosure·user 도메인 cross-domain 의존(MVP 한시 허용).
 *               tryInsertFeedback의 REQUIRES_NEW: 외부 트랜잭션과 별개로 커밋/롤백. 충돌 시 외부 tx가 살아있어 findByUserIdAndAnalysisId 재시도 가능.
 *               rateLimitCache는 인스턴스 로컬 — 수평 확장 시 각 인스턴스가 독립 카운터(레디스 마이그레이션 필요).
 *               재투표 시 created_at은 유지, updated_at만 갱신.
 * [수정 시 고려사항] INACCURATE 임계치 초과 시 분석 비공개 처리는 후속 Spec — 현재는 저장만.
 *                  rate-limit 한도(30건/시간)는 운영 데이터 확보 후 조정. 수평 확장 시 Redis로 교체.
 */
@Service
public class FeedbackService {

    private static final int    HOURLY_LIMIT = 30;

    private final FeedbackRepository       feedbackRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final DisclosureRepository     disclosureRepository;
    private final PortfolioRepository      portfolioRepository;

    // userId → 시간 내 피드백 횟수. expireAfterWrite으로 1시간마다 리셋.
    private final Cache<Long, AtomicInteger> rateLimitCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(10_000)
            .build();

    public FeedbackService(FeedbackRepository feedbackRepository,
                           AnalysisResultRepository analysisResultRepository,
                           DisclosureRepository disclosureRepository,
                           PortfolioRepository portfolioRepository) {
        this.feedbackRepository       = feedbackRepository;
        this.analysisResultRepository = analysisResultRepository;
        this.disclosureRepository     = disclosureRepository;
        this.portfolioRepository      = portfolioRepository;
    }

    @Transactional
    public void upsert(Long userId, Long analysisId, FeedbackRequest req) {
        // R3: rate-limit 먼저 체크 (DB 조회 전에 빠른 실패)
        checkRateLimit(userId);

        // R2: analysis 존재 + 포트폴리오 소유권 검증
        var analysisResult = analysisResultRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "분석 결과를 찾을 수 없습니다."));

        String stockCode = disclosureRepository.findStockCodeById(analysisResult.getDisclosureId())
                .orElse(null);
        if (stockCode == null || !portfolioRepository.existsByUserIdAndStockCode(userId, stockCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 결과를 찾을 수 없습니다.");
        }

        // R13: TOCTOU — find → upsert. 동시 INSERT 충돌 시 tryInsertFeedback(REQUIRES_NEW)가 독립 롤백되고
        //      외부 트랜잭션은 살아있어 findByUserIdAndAnalysisId 재시도로 안전하게 UPDATE 경로 진입.
        feedbackRepository.findByUserIdAndAnalysisId(userId, analysisId)
                .ifPresentOrElse(
                        existing -> existing.update(req.verdict(), req.reason()),
                        () -> tryInsertFeedback(userId, analysisId, req)
                );
    }

    /**
     * REQUIRES_NEW: 충돌(DataIntegrityViolationException) 시 이 트랜잭션만 롤백.
     * 외부 트랜잭션(upsert)은 정상 상태 유지 → 호출자가 재조회 후 UPDATE 경로 진입.
     * 충돌 예외는 외부로 전파해 upsert에서 재조회 UPDATE를 수행하도록 허용.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tryInsertFeedback(Long userId, Long analysisId, FeedbackRequest req) {
        feedbackRepository.findByUserIdAndAnalysisId(userId, analysisId)
                .ifPresentOrElse(
                        existing -> existing.update(req.verdict(), req.reason()),
                        () -> feedbackRepository.save(
                                FeedbackEntity.builder()
                                        .userId(userId)
                                        .analysisId(analysisId)
                                        .verdict(req.verdict())
                                        .reason(req.reason())
                                        .build()
                        )
                );
    }

    private void checkRateLimit(Long userId) {
        AtomicInteger count = rateLimitCache.get(userId, k -> new AtomicInteger(0));
        if (count.incrementAndGet() > HOURLY_LIMIT) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "피드백은 시간당 최대 " + HOURLY_LIMIT + "건까지 제출 가능합니다.");
        }
    }
}
