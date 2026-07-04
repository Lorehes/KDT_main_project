package com.dartcommons.infrastructure.oauth;

/*
 * [목적] OAuth provider별 사용자 정보를 통합하는 공통 DTO — KakaoOAuthClient / GoogleOAuthClient / NaverOAuthClient 공통 반환형.
 * [이유] provider마다 응답 구조가 다르지만(Kakao: kakao_account.email, Google: email, Naver: response.email),
 *       AuthService는 provider 종류에 무관하게 동일한 DTO로 처리하도록 추상화.
 * [사이드 임팩트] email이 null인 경우(provider가 이메일 권한 미부여) → AuthService에서 422 반환.
 * [수정 시 고려사항] nickname null 허용 — email prefix로 폴백 가능(AuthService에서 처리).
 *                  프로필 이미지 URL 추가 시 profileImageUrl 필드 확장.
 */
public record OAuthUserInfo(String providerId, String email, String nickname) {}
