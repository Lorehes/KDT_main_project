package com.dartcommons.user.dto;

import jakarta.validation.constraints.*;

/*
 * [목적] 이메일 회원가입 요청 DTO — 입력 검증 및 동의 플래그 수집.
 * [이유] terms·privacy·disclaimer 동의는 서비스 법적 요건 및 투자 면책 필수(CLAUDE.md §7, 통합기획서 §11.1).
 *       @AssertTrue로 필수 동의 미수락 시 400 반환.
 * [사이드 임팩트] AuthService.signup()이 이 DTO를 ConsentService 기록에 그대로 전달.
 * [수정 시 고려사항] 비밀번호 정책(최소 길이·복잡도) 강화 시 @Pattern 추가. BCrypt 최대 72자 제한 고려.
 */
public record SignupRequest(

        @NotBlank(message = "이메일을 입력해 주세요")
        @Email(message = "올바른 이메일 형식이 아닙니다")
        @Size(max = 255)
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하입니다")
        String password,

        @NotBlank(message = "닉네임을 입력해 주세요")
        @Size(min = 1, max = 50, message = "닉네임은 1~50자입니다")
        String nickname,

        @AssertTrue(message = "서비스 이용약관 동의가 필요합니다")
        boolean termsAgreed,

        @AssertTrue(message = "개인정보처리방침 동의가 필요합니다")
        boolean privacyAgreed,

        @AssertTrue(message = "투자 면책 조항 동의가 필요합니다")
        boolean disclaimerAgreed,

        boolean marketingAgreed
) {}
