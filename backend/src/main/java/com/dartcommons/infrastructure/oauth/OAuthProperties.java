package com.dartcommons.infrastructure.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
 * [목적] application.yml dartcommons.oauth.{kakao,google,naver}.* 를 타입 안전 record로 바인딩.
 * [이유] CLAUDE.md §7 OAuth API 키 하드코딩 금지 — 환경변수 주입 강제.
 *       DartApiProperties/JwtProperties와 동일한 record 패턴. @ConfigurationPropertiesScan 자동 발견.
 * [사이드 임팩트] @Validated 미적용 — 개발 환경에서 일부 provider만 설정할 수 있게 허용.
 *               미설정 provider는 application.yml 기본값("placeholder")으로 바인딩되어 NPE 방지.
 * [수정 시 고려사항] 신규 OAuth provider 추가 시 내부 record + application.yml + OAuthProviderClient 구현체 3종 추가.
 *                  client-secret 키 롤링은 환경변수 교체 + 앱 재시작으로 적용 가능.
 */
@ConfigurationProperties("dartcommons.oauth")
public record OAuthProperties(KakaoProps kakao, GoogleProps google, NaverProps naver) {

    public record KakaoProps(String clientId, String clientSecret, String redirectUri) {}

    public record GoogleProps(String clientId, String clientSecret, String redirectUri) {}

    public record NaverProps(String clientId, String clientSecret, String redirectUri) {}
}
