package com.dartcommons.user.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/*
 * [목적] consent_logs 테이블(V8) JPA 엔티티 — 동의 이력 INSERT-only 누적 보존(법적 증거).
 * [이유] users.*_agreed_at은 최초 시각만 기록 → 정책 재동의 시 이력 덮어씀.
 *       별도 이력 테이블로 버전별 동의를 불변 보존(통합기획서 §11.1, db_schema §3.7).
 * [사이드 임팩트] ON DELETE RESTRICT — 사용자 탈퇴(soft delete) 후에도 행 보존.
 *               INSERT-only 운영 규칙 — UPDATE/DELETE 호출 절대 금지(서비스 계층 강제).
 *               최신 동의 상태 = (user_id, consent_type) MAX(agreed_at) 행.
 * [수정 시 고려사항] policy_version은 'v{major}.{minor}' 시맨틱(DB CHECK 정규식).
 *                  메이저 버전 변경 시 재동의 요구 정책은 별도 후속 이슈.
 */
@Entity
@Table(name = "consent_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ConsentLogEntity {

    public enum ConsentType { TERMS, PRIVACY, DISCLAIMER, MARKETING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 15)
    private ConsentType consentType;

    @Column(name = "agreed", nullable = false)
    private boolean agreed;

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private OffsetDateTime agreedAt;

    @PrePersist
    private void prePersist() {
        if (agreedAt == null) agreedAt = OffsetDateTime.now();
    }
}
