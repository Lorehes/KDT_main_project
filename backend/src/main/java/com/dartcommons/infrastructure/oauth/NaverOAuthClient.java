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
 * [목적] 네이버 OAuth 2.0 인증 클라이언트 — nid.naver.com 토큰 교환 + openapi.naver.com 사용자 정보 조회.
 * [이유] 네이버 토큰 교환은 state 파라미터를 필수로 포함(Naver OAuth 2.0 스펙). CSRF 검증 후 state를 그대로 전달.
 *       RestClient + @Retryable — DartClient/KakaoOAuthClient/GoogleOAuthClient와 동일한 패턴.
 * [사이드 임팩트] 네이버는 response.id(문자열)로 providerId 반환 — 카카오(Long)와 달리 형변환 불필요.
 *               네이버 이메일 비동의 시 userResp.response().email()이 null → AuthService에서 422 처리.
 * [수정 시 고려사항] 네이버 API 버전 변경(v1/nid/me → 상위 버전) 시 apiClient URI만 수정.
 *                  토큰 갱신(refresh_token) 필요 시 별도 refreshAccessToken() 메서드 추가.
 */
@Component
public class NaverOAuthClient implements OAuthProviderClient {

    private static final Logger log = LoggerFactory.getLogger(NaverOAuthClient.class);

    private final RestClient authClient;
    private final RestClient apiClient;
    private final OAuthProperties.NaverProps props;

    public NaverOAuthClient(OAuthProperties oauthProperties) {
        this.props = oauthProperties.naver();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.authClient = RestClient.builder().requestFactory(factory).baseUrl("https://nid.naver.com").build();
        this.apiClient  = RestClient.builder().requestFactory(factory).baseUrl("https://openapi.naver.com").build();
    }

    @Override
    public String getProviderName() { return "naver"; }

    @Override
    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder
                .fromUriString("https://nid.naver.com/oauth2.0/authorize")
                .queryParam("client_id",    props.clientId())
                .queryParam("redirect_uri", props.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("state",        state)
                .build().toUriString();
    }

    @Override
    @Retryable(retryFor = Exception.class, noRetryFor = org.springframework.web.client.HttpClientErrorException.class,
               maxAttempts = 2, backoff = @Backoff(delay = 500))
    public OAuthUserInfo getUserInfo(String code, String state) {
        // 1. 인가 코드 → 액세스 토큰 (Naver는 state 필수)
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type",    "authorization_code");
        form.add("client_id",     props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("code",          code);
        form.add("state",         state);

        NaverTokenResponse tokenResp = authClient.post()
                .uri("/oauth2.0/token")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(NaverTokenResponse.class);
        if (tokenResp == null) throw new IllegalStateException("네이버 토큰 응답이 비어있습니다");

        // 2. 사용자 정보 조회
        NaverUserResponse userResp = apiClient.get()
                .uri("/v1/nid/me")
                .header("Authorization", "Bearer " + tokenResp.accessToken())
                .retrieve()
                .body(NaverUserResponse.class);
        if (userResp == null || userResp.response() == null)
            throw new IllegalStateException("네이버 사용자 정보 응답이 비어있습니다");

        NaverProfile profile = userResp.response();
        log.debug("naver getUserInfo: providerId={}", profile.id());
        return new OAuthUserInfo(profile.id(), profile.email(), profile.name());
    }

    // ── Naver API 응답 내부 record ──────────────────────────────────────────
    private record NaverTokenResponse(@JsonProperty("access_token") String accessToken) {}

    private record NaverUserResponse(String resultcode, String message, NaverProfile response) {}

    private record NaverProfile(String id, String email, String name) {}
}
