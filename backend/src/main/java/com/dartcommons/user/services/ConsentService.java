package com.dartcommons.user.services;

import com.dartcommons.user.dto.ConsentStatusResponse;
import com.dartcommons.user.entities.ConsentLogEntity;
import com.dartcommons.user.entities.ConsentLogEntity.ConsentType;
import com.dartcommons.user.repositories.ConsentLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/*
 * [목적] 동의 이력 INSERT-only 기록 + 최신 동의 상태 조회 + 재동의 필요 여부 산출.
 *       consent_logs 테이블은 불변 이력 — UPDATE/DELETE 절대 금지(통합기획서 §11.1).
 * [이유] 서비스 약관·개인정보처리방침·면책조항 동의는 법적 증거로 보존 필요.
 *       정책 버전이 바뀔 때마다 새 행을 INSERT해 버전별 동의 시각을 추적.
 *       getStatus()는 (user_id, consent_type) MAX(agreed_at) 최신 행과 CURRENT_POLICY_VERSION 대조 →
 *       requires_renewal 산출로 FE가 재동의 흐름을 트리거할 수 있게 함.
 * [사이드 임팩트] AuthService.signup()이 recordSignupConsents() 호출 — 회원가입 트랜잭션 내 4개 INSERT.
 *               getStatus()는 읽기 전용 — 트랜잭션 propagation REQUIRED이나 dirty read 없음.
 * [수정 시 고려사항] CURRENT_POLICY_VERSION 변경 시 기존 사용자 getStatus() 응답이 requires_renewal=true 됨.
 *                  메이저 버전 변경 시 별도 스케줄러/이메일 발송으로 사용자 안내 병행 권장.
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

    /**
     * 재동의 흐름 진입 판단 — 사용자의 최신 동의 이력과 CURRENT_POLICY_VERSION 대조.
     * requires_renewal=true 조건: TERMS 또는 PRIVACY 최신 동의의 policy_version이 현재 버전과 불일치하거나 동의 이력 없음.
     * findLatestAllByUserId()로 단일 쿼리 조회 — N+1 제거.
     */
    @Transactional(readOnly = true)
    public ConsentStatusResponse getStatus(Long userId) {
        Map<ConsentType, ConsentLogEntity> latestMap = consentLogRepository.findLatestAllByUserId(userId)
                .stream()
                .collect(Collectors.toMap(ConsentLogEntity::getConsentType, e -> e));

        List<ConsentStatusResponse.ConsentItem> items = Arrays.stream(ConsentType.values())
                .map(type -> {
                    ConsentLogEntity entry = latestMap.get(type);
                    if (entry == null) {
                        return new ConsentStatusResponse.ConsentItem(
                                type.name(), false, null, false, null);
                    }
                    return new ConsentStatusResponse.ConsentItem(
                            type.name(),
                            entry.isAgreed(),
                            entry.getPolicyVersion(),
                            CURRENT_POLICY_VERSION.equals(entry.getPolicyVersion()),
                            entry.getAgreedAt());
                })
                .collect(Collectors.toList());

        // 필수 동의(TERMS·PRIVACY) 중 하나라도 이력 없거나 버전 불일치 시 재동의 필요
        boolean requiresRenewal = List.of(ConsentType.TERMS, ConsentType.PRIVACY).stream()
                .anyMatch(type -> {
                    ConsentLogEntity entry = latestMap.get(type);
                    return entry == null || !CURRENT_POLICY_VERSION.equals(entry.getPolicyVersion());
                });

        return new ConsentStatusResponse(requiresRenewal, items);
    }

    /**
     * 재동의 흐름에서 TERMS·PRIVACY·MARKETING 3종 기록.
     * 실제 동의한 정책 버전을 기록해 감사 로그 정확도 보장(정보통신망법 §22).
     * termsVersion·privacyVersion null이면 422 BUSINESS_RULE_VIOLATION.
     */
    public void recordReConsents(Long userId, String termsVersion, String privacyVersion, Boolean marketingOptIn) {
        if (termsVersion == null || privacyVersion == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION");
        }
        var logs = new java.util.ArrayList<ConsentLogEntity>();
        logs.add(buildLogWithVersion(userId, ConsentType.TERMS,     true, termsVersion));
        logs.add(buildLogWithVersion(userId, ConsentType.PRIVACY,   true, privacyVersion));
        logs.add(buildLog(userId, ConsentType.MARKETING, Boolean.TRUE.equals(marketingOptIn)));
        consentLogRepository.saveAll(logs);
    }

    private ConsentLogEntity buildLogWithVersion(Long userId, ConsentType type, boolean agreed, String version) {
        return ConsentLogEntity.builder()
                .userId(userId)
                .consentType(type)
                .agreed(agreed)
                .policyVersion(version)
                .build();
    }
}
