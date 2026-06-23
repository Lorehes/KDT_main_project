package com.dartcommons.user;

import com.dartcommons.user.repositories.RefreshTokenRepository;
import com.dartcommons.user.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/*
 * [목적] 매일 새벽 3시, OAuth 가입 후 온보딩 미완료(onboarding_completed_at IS NULL) 상태로
 *       3일이 경과한 좀비 계정을 soft delete해 미사용 개인정보 보유 위험 완화.
 * [이유] 정보통신망법 §29(안전조치): 동의 미완료 계정이 3일+ 이상 이메일/OAuth ID 등 개인정보를 보유하면
 *       미사용 개인정보로 간주될 수 있음. oauth-consent-data-integrity Spec M-M2 이슈.
 *       삭제 기준: oauth_provider IS NOT NULL(OAuth 가입) + onboarding_completed_at IS NULL(온보딩 미완료)
 *                + created_at < now()-3일 + deleted_at IS NULL(아직 soft delete 아닌 계정).
 *       hard delete 금지: consent_logs는 INSERT-only 불변 이력(통합기획서 §11.1) —
 *                hard delete 시 동의 거부 이력 소실. soft delete로 비활성화만 수행.
 * [사이드 임팩트] soft delete 후 users.deleted_at 설정 → findByEmailAndDeletedAtIsNull이 해당 계정 제외.
 *               refresh_tokens는 ON DELETE CASCADE 미발생(soft delete는 DB row 유지) — 애플리케이션 레이어에서 먼저 삭제.
 *               soft delete 계정의 JWT는 만료(30분) 대기 — 온보딩 미완료 계정이므로 실사용 경로 없음.
 *               consent_logs 행은 보존됨 — 동의 거부 이력 추적 가능.
 * [수정 시 고려사항] ZOMBIE_GRACE_DAYS(현재 3일)는 법무팀 정책에 따라 application.yml 외부화 권장.
 *                  수평 확장 시 @SchedulerLock(ShedLock) 추가 필요(NotificationRetryJob 패턴 참조) — MVP: 단일 인스턴스.
 *                  hard delete + consent_logs 보존이 필요한 GDPR 정책(EU/개인정보보호법)은 별도 배치 담당.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dartcommons.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class OAuthIncompleteAccountCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OAuthIncompleteAccountCleanupJob.class);

    private static final int ZOMBIE_GRACE_DAYS = 3;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupIncompleteAccounts() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusDays(ZOMBIE_GRACE_DAYS);

        List<Long> targetIds = userRepository.findIncompleteOAuthAccountIds(cutoff);

        if (targetIds.isEmpty()) {
            log.debug("CleanupJob: no incomplete OAuth accounts to remove (grace={}d)", ZOMBIE_GRACE_DAYS);
            return;
        }

        // refresh_tokens 먼저 삭제 — ON DELETE CASCADE 미발생(soft delete), 토큰 즉시 무효화
        int tokensRevoked = refreshTokenRepository.deleteByUserIdIn(targetIds);

        // users soft delete — hard delete 금지(consent_logs 보존 의무, 통합기획서 §11.1)
        int softDeleted = userRepository.softDeleteByIdIn(targetIds, now);

        log.info("CleanupJob: soft-deleted={} incomplete OAuth accounts, tokens-revoked={} (grace={}d, cutoff={})",
                softDeleted, tokensRevoked, ZOMBIE_GRACE_DAYS, cutoff);
    }
}
