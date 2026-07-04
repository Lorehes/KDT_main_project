package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/*
 * [목적] PATCH /api/v1/users/me 요청 DTO — 닉네임·투자 경험·주 사용 시점 변경.
 * [이유] 이메일은 변경 불가(인증 기준값), 패스워드는 별도 흐름(OAuth 사용자 고려).
 *       nickname nullable 채택(V22 결정): profile 단계는 새로고침 시 user=null이어도 안전하게 두 필드만 전송 가능.
 *       기존 마이페이지 닉네임 변경은 nickname 포함 전송 → 동일 동작(하위 호환).
 * [사이드 임팩트] nickname null이면 UserService에서 updateNickname 스킵 — 두 필드만 전송하는 profile 단계에 사용.
 * [수정 시 고려사항] nickname: null → 스킵(profile 단계), "" → @Size(min=1) 위반 → 400. null과 ""은 다름 주의.
 *                  investmentExperience/preferredTime: null → @Pattern 통과(Jakarta 스펙), 잘못된 값 → 400.
 *                  @Pattern이 컨트롤러 단계에서 차단하므로 서비스 valueOf() 실패 없음.
 *                  @JsonProperty snake_case: GET /users/me 응답(UserMeResponse)과 키 일관성 유지.
 *                  닉네임 중복 허용 정책 확정 후 uniqueness 검사 추가 여부 결정.
 */
public record UpdateMeRequest(
        @Size(min = 1, max = 50) String nickname,
        @JsonProperty("investment_experience") @Pattern(regexp = "BEGINNER|INTERMEDIATE|ADVANCED") String investmentExperience,
        @JsonProperty("preferred_time")        @Pattern(regexp = "REALTIME|LUNCH|EVENING")         String preferredTime
) {}
