package com.dartcommons.disclosure.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/*
 * [목적] DART 공시 1건을 disclosures 테이블(V4 마이그레이션)에 매핑하는 JPA 엔티티.
 *       rcept_no UNIQUE 제약이 중복 적재 방어의 DB 2차 게이트.
 * [이유] Stage 1 룰 추출(메타+유형 분류)의 결과물을 영속화. ddl-auto: validate 통과를 확인해
 *       V4 스키마와 정합성을 유지(CLAUDE.md §6-3).
 * [사이드 임팩트] analysis_results(V5)·notifications(V6)가 disclosures.id FK 참조 — 삭제 불가.
 *               stock_code는 stocks(V2)의 FK이나 JPA 관계 없이 String으로 관리(stocks 엔티티 미생성).
 *               비상장 공시(stock_code=null)는 커버 종목 필터에서 skip되므로 실제 null 저장은 없음.
 * [수정 시 고려사항] corp_name·report_nm은 DART 원본 그대로 — 서비스 계층이 절대 변형 금지(CLAUDE.md §4).
 *                  content_text(본문)·attachment_url은 Stage 1 범위 밖이라 null 저장(후속 Spec).
 *                  disclosureType은 DisclosureTypeClassifier 룰 결과 — 분류 실패 시 "OTHER".
 */
@Entity
@Table(name = "disclosures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Disclosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rcept_no", nullable = false, unique = true, length = 14)
    private String rceptNo;

    @Column(name = "corp_code", nullable = false, length = 8)
    private String corpCode;

    /** 비상장은 null. stocks(V2) FK는 DB 레벨 — JPA 관계 미선언(stocks 엔티티 후속). */
    @Column(name = "stock_code", length = 6)
    private String stockCode;

    /** DART 원본 그대로 보존 — LLM 변형 절대 금지(CLAUDE.md §4) */
    @Column(name = "corp_name", nullable = false, length = 100)
    private String corpName;

    /** DART 원본 그대로 보존 */
    @Column(name = "report_nm", nullable = false, length = 255)
    private String reportNm;

    @Column(name = "rcept_dt", nullable = false)
    private LocalDate rceptDt;

    /** DisclosureTypeClassifier 룰 분류 결과. 미분류는 "OTHER". */
    @Column(name = "disclosure_type", nullable = false, length = 50)
    private String disclosureType;

    /** 본문 추출 텍스트 — Stage 1 범위 밖, 후속 Spec에서 채움 */
    @Column(name = "content_text")
    private String contentText;

    /** 대용량 원문 참조 URL — 후속 Spec */
    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (collectedAt == null) collectedAt = now;
        if (createdAt == null) createdAt = now;
    }
}
