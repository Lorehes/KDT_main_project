package com.dartcommons.user.services;

import com.dartcommons.user.entities.ConsentLogEntity;
import com.dartcommons.user.entities.ConsentLogEntity.ConsentType;
import com.dartcommons.user.repositories.ConsentLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/*
 * [목적] 동의 이력 INSERT-only 기록 및 최신 동의 상태 조회.
 *       consent_logs 테이블은 불변 이력 — UPDATE/DELETE 절대 금지(통합기획서 §11.1).
 * [이유] 서비스 약관·개인정보처리방침·면책조항 동의는 법적 증거로 보존 필요.
 *       정책 버전이 바뀔 때마다 새 행을 INSERT해 버전별 동의 시각을 추적.
 * [사이드 임팩트] AuthService.signup()이 호출 — 회원가입 트랜잭션 내에서 4개 consent_log 행 INSERT.
 * [수정 시 고려사항] policy_version 관리 정책 확정 시 상수(CURRENT_POLICY_VERSION) → 설정값 이관.
 *                  메이저 버전 변경 시 기존 사용자 재동의 요구 정책은 별도 스케줄러/이메일 발송 로직.
 */
@Service
@Transactional
public class ConsentService {

    static final String CURRENT_POLICY_VERSION = "v1.0";

    private final ConsentLogRepository consentLogRepository;

    public ConsentService(ConsentLogRepository consentLogRepository) {
        this.consentLogRepository = consentLogRepository;
    }

    /** 단일 동의 항목을 이력에 INSERT. agreed=false도 기록(명시적 거부 이력). */
    public void record(Long userId, ConsentType type, boolean agreed) {
        ConsentLogEntity log = ConsentLogEntity.builder()
                .userId(userId)
                .consentType(type)
                .agreed(agreed)
                .policyVersion(CURRENT_POLICY_VERSION)
                .build();
        consentLogRepository.save(log);
    }

    /** 회원가입 시 4개 동의 항목 일괄 기록 (saveAll batch INSERT). */
    public void recordSignupConsents(Long userId,
                                     boolean termsAgreed,
                                     boolean privacyAgreed,
                                     boolean disclaimerAgreed,
                                     boolean marketingAgreed) {
        consentLogRepository.saveAll(List.of(
                buildLog(userId, ConsentType.TERMS,      termsAgreed),
                buildLog(userId, ConsentType.PRIVACY,    privacyAgreed),
                buildLog(userId, ConsentType.DISCLAIMER, disclaimerAgreed),
                buildLog(userId, ConsentType.MARKETING,  marketingAgreed)
        ));
    }

    private ConsentLogEntity buildLog(Long userId, ConsentType type, boolean agreed) {
        return ConsentLogEntity.builder()
                .userId(userId)
                .consentType(type)
                .agreed(agreed)
                .policyVersion(CURRENT_POLICY_VERSION)
                .build();
    }

    /** (user_id, consent_type) 기준 최신 동의 상태 조회. */
    @Transactional(readOnly = true)
    public Optional<ConsentLogEntity> findLatest(Long userId, ConsentType type) {
        return consentLogRepository.findLatestByUserIdAndType(userId, type);
    }
}
