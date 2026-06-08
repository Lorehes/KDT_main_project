package com.dartcommons.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * [목적] PATCH /api/v1/users/me 요청 DTO — 닉네임 변경.
 * [이유] 이메일은 변경 불가(인증 기준값), 패스워드는 별도 흐름(OAuth 사용자 고려).
 *       MVP에서는 닉네임만 변경 가능. 이후 비밀번호 변경·탈퇴 사유 등 확장 가능.
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] 닉네임 중복 허용 정책 확정 후 uniqueness 검사 추가 여부 결정.
 */
public record UpdateMeRequest(
        @NotBlank @Size(min = 1, max = 50) String nickname
) {}
