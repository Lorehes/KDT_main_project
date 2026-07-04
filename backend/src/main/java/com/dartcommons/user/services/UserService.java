package com.dartcommons.user.services;

import com.dartcommons.user.dto.UpdateMeRequest;
import com.dartcommons.user.dto.UserMeResponse;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.RefreshTokenRepository;
import com.dartcommons.user.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/*
 * [목적] 사용자 프로필 조회·수정·soft-delete·온보딩 완료 마킹 서비스.
 * [이유] 닉네임 변경·계정 탈퇴는 인증된 사용자 본인만 수행 — 컨트롤러가 SecurityContext에서 userId를 추출해 전달.
 *       soft delete 시 refresh_tokens 일괄 삭제로 전 기기 즉시 로그아웃.
 *       completeOnboarding()은 /signup/complete 도달 시 호출 — OAuth is_new_user 판단 기준 전환.
 * [사이드 임팩트] softDeleteMe()는 refresh_tokens 삭제 → 모든 기기에서 access token 만료(30분) 전까지는 유효.
 *               portfolios/consent_logs 등 사용자 데이터는 soft delete 후 보존(GDPR 배치 처리 별도).
 *               completeOnboarding()은 이미 완료된 경우 멱등(UserEntity.completeOnboarding() 내부 guard).
 *               updateMe() — V22: nickname null 허용(profile 단계 미전송 허용), 프로필 두 필드 null-safe 업데이트.
 * [수정 시 고려사항] 이메일 변경 기능 추가 시 이메일 인증 흐름 필요(MVP 외).
 *                  계정 복구(undelete) 정책 확정 전 softDelete()는 되돌릴 수 없는 것으로 간주.
 *                  investmentExperience/preferredTime은 해석 복잡도 조정 전용 — 투자 권유 판단에 활용 금지.
 */
@Service
@Transactional
public class UserService {

    private final UserRepository          userRepository;
    private final RefreshTokenRepository  refreshTokenRepository;

    public UserService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository) {
        this.userRepository         = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(readOnly = true)
    public UserMeResponse getMe(Long userId) {
        UserEntity user = findActiveUser(userId);
        return UserMeResponse.from(user);
    }

    public UserMeResponse updateMe(Long userId, UpdateMeRequest request) {
        UserEntity user = findActiveUser(userId);
        // nickname null = profile 단계에서 미전송 → 스킵. 빈 문자열은 @Size(min=1)이 컨트롤러에서 400 처리.
        if (request.nickname() != null) {
            user.updateNickname(request.nickname());
        }
        // enum 문자열은 @Pattern이 컨트롤러에서 검증 완료 — valueOf() 실패 없음.
        UserEntity.InvestmentExperience experience = request.investmentExperience() != null
                ? UserEntity.InvestmentExperience.valueOf(request.investmentExperience()) : null;
        UserEntity.PreferredTime preferredTime = request.preferredTime() != null
                ? UserEntity.PreferredTime.valueOf(request.preferredTime()) : null;
        user.updateProfile(experience, preferredTime);
        return UserMeResponse.from(user);
    }

    /** 온보딩 완료 마킹 — /signup/complete 진입 시 호출. 멱등(UserEntity 내부 guard). */
    public void completeOnboarding(Long userId) {
        UserEntity user = findActiveUser(userId);
        user.completeOnboarding();
    }

    /** soft delete + 전 기기 로그아웃(refresh token 일괄 삭제). */
    public void softDeleteMe(Long userId) {
        UserEntity user = findActiveUser(userId);
        user.softDelete();
        refreshTokenRepository.deleteByUserId(userId);
    }

    private UserEntity findActiveUser(Long userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"));
    }
}
