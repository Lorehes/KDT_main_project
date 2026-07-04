package com.dartcommons.analysis.entities;

import com.dartcommons.shared.enums.Sentiment;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/*
 * [목적] LLM 분석 결과 1건(Stage 2~5) — analysis_results(V5) 매핑.
 *       공시당 최종 1건(uq_analysis_disclosure UNIQUE).
 * [이유] 호재/중립/악재 분류·요약·근거의 저장처. 환각 방지 위해 LLM 응답을 record로 파싱 후 저장(CLAUDE.md §6-6).
 * [사이드 임팩트] V9 feedbacks가 본 엔티티 id를 FK 참조 — 삭제 시 cascade 영향.
 *               disclosure 1:1 — UPSERT는 application 계층에서 findByDisclosureId → save 패턴.
 * [수정 시 고려사항] confidence는 NUMERIC(4,3) → BigDecimal (정밀도 보존, double 금지).
 *                  is_withheld=true면 호재/악재 단정 대신 "판단 보류" 표시(api_spec §2.4).
 *                  stageDetails(JSONB)는 본 Spec=2에서는 null 또는 raw 응답 String — wave 2에서 JSON 컨버터 검토.
 */
@Entity
@Table(name = "analysis_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AnalysisResult {

    // Sentiment enum 이관: shared.enums.Sentiment (sentiment-to-shared, 2026-06-08)
    public enum ExpectedReaction { UP, FLAT, DOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "disclosure_id", nullable = false)
    private Long disclosureId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sentiment", nullable = false, length = 10)
    private Sentiment sentiment;

    @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "is_withheld", nullable = false)
    private boolean withheld;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    /** Stage 4(Pro+) — Stage 2에서는 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "expected_reaction", length = 20)
    private ExpectedReaction expectedReaction;

    /** Stage 4(Pro+) — Stage 2에서는 null. */
    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "stage_reached", nullable = false)
    private short stageReached;

    /**
     * JSONB. disclosure-detail-redesign Wave 2부터 Stage2Detail(key_points/호재·악재 요인) JSON 저장.
     * 구버전 분석·요인 전무 시 null. Stage2Analyzer가 직렬화, AnalysisQueryService가 역직렬화.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stage_details", columnDefinition = "jsonb")
    private String stageDetails;

    @Column(name = "model_name", length = 50)
    private String modelName;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
