package com.dartcommons.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/*
 * [목적] 이메일 로그인 요청 DTO.
 * [이유] email/password 검증은 최소한(형식·공백)만 — 상세 오류는 "인증 실패" 단일 메시지로 통일(정보 노출 방지).
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] 로그인 시도 횟수 제한(rate-limit) 추가 시 AuthController에 AOP/Bucket4j 적용.
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {}
