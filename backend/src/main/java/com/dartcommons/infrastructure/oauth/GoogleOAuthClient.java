package com.dartcommons.infrastructure.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;

/*
 * [목적] 구글 OAuth 2.0 인증 클라이언트 — oauth2.googleapis.com 토큰 교환 + googleapis.com/userinfo 조회.
 * [이유] Google Identity Platform은 scope=email profile로 이메일·이름을 함께 반환.
 *       RestClient + @Retryable(5xx 재시도 1회) — DartClient, KakaoOAuthClient와 동일한 패턴.
 * [사이드 임팩트] 구글 계정의 이메일은 항상 존재 — null 케이스는 실질적으로 발생하지 않으나 방어 처리.
 * [수정 시 고려사항] PKCE(code_verifier/code_challenge) 도입 시 buildAuthorizationUrl + getUserInfo 확장.
 *                  openid scope 추가 시 id_token JWT 파싱으로 userinfo 호출 생략 가능.
 */
@Component
public class GoogleOAuthClient implements OAuthProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthClient.class);

    private final RestClient authClient;
    private final RestClient apiClient;
    private final OAuthProperties.GoogleProps props;

    public GoogleOAuthClient(OAuthProperties oauthProperties) {
        this.props = oauthProperties.google();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.authClient = RestClient.builder().requestFactory(factory).baseUrl("https://oauth2.googleapis.com").build();
        this.apiClient  = RestClient.builder().requestFactory(factory).baseUrl("https://www.googleapis.com").build();
    }

    @Override
    public String getProviderName() { return "google"; }

    @Override
    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder
                .fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id",    props.clientId())
                .queryParam("redirect_uri", props.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope",        "email profile")
                .queryParam("state",        state)
                .build().toUriString();
    }

    @Override
    @Retryable(retryFor = Exception.class, noRetryFor = org.springframework.web.client.HttpClientErrorException.class,
               maxAttempts = 2, backoff = @Backoff(delay = 500))
    public OAuthUserInfo getUserInfo(String code, String state) {
        // 1. 인가 코드 → 액세스 토큰 (state는 Google 토큰 교환에서 불필요)
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code",          code);
        form.add("client_id",     props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("redirect_uri",  props.redirectUri());
        form.add("grant_type",    "authorization_code");

        GoogleTokenResponse tokenResp = authClient.post()
                .uri("/token")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(GoogleTokenResponse.class);
        if (tokenResp == null) throw new IllegalStateException("구글 토큰 응답이 비어있습니다");

        // 2. 사용자 정보 조회
        GoogleUserResponse userResp = apiClient.get()
                .uri("/oauth2/v2/userinfo")
                .header("Authorization", "Bearer " + tokenResp.accessToken())
                .retrieve()
                .body(GoogleUserResponse.class);
        if (userResp == null) throw new IllegalStateException("구글 사용자 정보 응답이 비어있습니다");

        log.debug("google getUserInfo: providerId={}", userResp.id());
        return new OAuthUserInfo(userResp.id(), userResp.email(), userResp.name());
    }

    // ── Google API 응답 내부 record ─────────────────────────────────────────
    private record GoogleTokenResponse(@JsonProperty("access_token") String accessToken) {}

    private record GoogleUserResponse(String id, String email, String name) {}
}
