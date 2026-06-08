package com.dartcommons.infrastructure.oauth;

/*
 * [목적] OAuth provider별 인증 클라이언트 추상화 — KakaoOAuthClient / GoogleOAuthClient / NaverOAuthClient 공통 인터페이스.
 * [이유] AuthService가 provider 이름으로 OAuthProviderClient를 동적 선택하여 동일한 흐름(code→token→userInfo)으로 처리.
 *       Spring이 List<OAuthProviderClient>를 자동 수집하고 AuthService가 Map으로 인덱싱.
 * [사이드 임팩트] 신규 provider 추가 시 이 인터페이스 구현체만 추가하면 AuthService 코드 변경 불필요.
 * [수정 시 고려사항] Naver 토큰 교환 시 state가 필요하므로 getUserInfo에 state 파라미터 포함.
 *                  Kakao/Google 구현체는 state를 무시하고 Naver만 토큰 요청에 포함.
 */
public interface OAuthProviderClient {

    /** provider 식별자 — AuthService의 Map key로 사용. 소문자 (kakao / google / naver). */
    String getProviderName();

    /** OAuth 인가 URL 생성 — state CSRF 파라미터 포함. */
    String buildAuthorizationUrl(String state);

    /**
     * 인가 코드(code)를 액세스 토큰으로 교환 후 사용자 정보 조회.
     * Naver는 state를 토큰 요청에 포함 — 다른 provider는 무시해도 됨.
     */
    OAuthUserInfo getUserInfo(String code, String state);
}
