package com.dartcommons.analysis;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.analysis.dto.FeedbackRequest;
import com.dartcommons.analysis.entities.FeedbackEntity.Verdict;
import com.dartcommons.analysis.repositories.FeedbackRepository;
import com.dartcommons.analysis.services.FeedbackService;
import com.dartcommons.analysis.services.AnalysisOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * [목적] FeedbackService.upsert() 통합 테스트 — upsert 패턴·IDOR·rate-limit 로직 검증.
 * [이유] UNIQUE 제약 기반 upsert는 실 DB(Testcontainers) 없이 재현 불가(CLAUDE.md §6-6 Mock DB 금지).
 *       AnalysisControllerTest(HTTP 계층)와 역할 분담: 서비스 계층 단위 시나리오 직접 호출.
 * [사이드 임팩트] FeedbackRepository 통해 DB 검증 — AnalysisControllerTest와 동일 Testcontainers 인스턴스 공유.
 * [수정 시 고려사항] rate-limit 테스트는 FeedbackService 인스턴스가 테스트 간 공유될 수 있어
 *                  Caffeine 캐시 상태가 오염될 수 있음. 현재 30건 초과 케이스는 별도 @Isolated 환경 필요.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "dartcommons.dart.api-key=test-key",
        "dartcommons.dart.base-url=http://localhost",
        "dartcommons.krx.api-key=test-key",
        "dartcommons.krx.base-url=http://localhost",
        "dartcommons.admin.username=admin",
        "dartcommons.admin.password=test-admin-password",
        "dartcommons.llm.provider=mock"
})
class FeedbackServiceIntegrationTest {

    @MockitoBean AnalysisOrchestrator analysisOrchestrator;

    @Autowired FeedbackService    feedbackService;
    @Autowired FeedbackRepository feedbackRepository;
    @Autowired JdbcTemplate       jdbc;

    private String uniqueEmail() {
        return "fst-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    private String uniqueRceptNo() {
        return "2025" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /**
     * 테스트용 사용자·포트폴리오·분析 결과를 직접 삽입하고 (userId, analysisId) 반환.
     */
    private long[] setupUserWithAnalysis(String stockCode) {
        String email = uniqueEmail();
        Long userId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, tier, terms_agreed_at, privacy_agreed_at, created_at, updated_at) " +
                "VALUES (?, '$2a$10$test', '테스터', 'FREE', now(), now(), now(), now()) RETURNING id",
                Long.class, email);
        userId = Objects.requireNonNull(userId);

        jdbc.update("INSERT INTO portfolios (user_id, stock_code) VALUES (?, ?)", userId, stockCode);

        Long discId = jdbc.queryForObject(
                "INSERT INTO disclosures (rcept_no, corp_code, stock_code, corp_name, report_nm, rcept_dt, disclosure_type, collected_at) " +
                "VALUES (?, '00000001', ?, '테스트회사', '테스트공시', '2025-06-01'::date, 'A001', now()) RETURNING id",
                Long.class, uniqueRceptNo(), stockCode);
        discId = Objects.requireNonNull(discId);

        Long analysisId = jdbc.queryForObject(
                "INSERT INTO analysis_results (disclosure_id, sentiment, confidence, is_withheld, summary, expected_reaction, rationale, stage_reached) " +
                "VALUES (?, 'POSITIVE', 0.850, false, '테스트 요약', 'UP', '근거', 2) RETURNING id",
                Long.class, discId);

        return new long[]{userId, Objects.requireNonNull(analysisId)};
    }

    @Test
    @DisplayName("신규 피드백 INSERT — DB에 1건 저장")
    void upsert_insert_savesOneFeedback() {
        long[] ids = setupUserWithAnalysis("005930");
        long userId = ids[0], analysisId = ids[1];

        feedbackService.upsert(userId, analysisId, new FeedbackRequest(Verdict.USEFUL, null));

        long count = feedbackRepository.findByUserIdAndAnalysisId(userId, analysisId)
                .map(f -> 1L).orElse(0L);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("재투표 UPDATE — verdict가 INACCURATE로 갱신")
    void upsert_reVote_updatesVerdict() {
        long[] ids = setupUserWithAnalysis("005930");
        long userId = ids[0], analysisId = ids[1];

        feedbackService.upsert(userId, analysisId, new FeedbackRequest(Verdict.USEFUL, null));
        feedbackService.upsert(userId, analysisId, new FeedbackRequest(Verdict.INACCURATE, "근거 없음"));

        String verdict = jdbc.queryForObject(
                "SELECT verdict FROM feedbacks WHERE user_id = ? AND analysis_id = ?",
                String.class, userId, analysisId);
        assertThat(verdict).isEqualTo("INACCURATE");

        // 행이 1건만 존재하는지 확인 (INSERT 중복 없음)
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM feedbacks WHERE user_id = ? AND analysis_id = ?",
                Integer.class, userId, analysisId);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    @DisplayName("포트폴리오 미소유 — 404 ResponseStatusException (IDOR 방어)")
    void upsert_noPortfolioOwnership_throws404() {
        long[] ids = setupUserWithAnalysis("005930");
        long analysisId = ids[1];

        // 다른 사용자 ID (000660 포트폴리오도 없는 신규 사용자)
        Long otherUserId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, tier, terms_agreed_at, privacy_agreed_at, created_at, updated_at) " +
                "VALUES (?, '$2a$10$test', '다른사용자', 'FREE', now(), now(), now(), now()) RETURNING id",
                Long.class, uniqueEmail());

        assertThatThrownBy(() ->
                feedbackService.upsert(Objects.requireNonNull(otherUserId), analysisId, new FeedbackRequest(Verdict.USEFUL, null))
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("분석 결과를 찾을 수 없습니다");
    }
}
