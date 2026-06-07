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
 * [목적] 카카오 OAuth 2.0 인증 클라이언트 — kauth.kakao.com 토큰 교환 + kapi.kakao.com 사용자 정보 조회.
 * [이유] RestClient(Spring 6.1+) 사용 — WebClient 대비 동기 블로킹 모델이 OAuth 1회성 콜백에 적합.
 *       @Retryable: 카카오 서버 일시 오류(5xx) 시 1회 재시도. 4xx(invalid_grant 등)는 재시도 없이 즉시 실패.
 * [사이드 임팩트] getUserInfo는 2번의 외부 HTTP 호출(토큰 + 유저 정보) — 카카오 장애 시 응답 지연.
 * [수정 시 고려사항] 카카오 알림톡 수신동의 확인 필요 시 kakao_account.allowed_service_notis 필드 추가.
 *                  이메일 비동의 케이스 — kakao_account.email이 null일 때 AuthService에서 처리.
 */
@Component
public class KakaoOAuthClient implements OAuthProviderClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoOAuthClient.class);

    private final RestClient authClient;
    private final RestClient apiClient;
    private final OAuthProperties.KakaoProps props;

    public KakaoOAuthClient(OAuthProperties oauthProperties) {
        this.props = oauthProperties.kakao();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.authClient = RestClient.builder().requestFactory(factory).baseUrl("https://kauth.kakao.com").build();
        this.apiClient  = RestClient.builder().requestFactory(factory).baseUrl("https://kapi.kakao.com").build();
    }

    @Override
    public String getProviderName() { return "kakao"; }

    @Override
    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder
                .fromUriString("https://kauth.kakao.com/oauth/authorize")
                .queryParam("client_id",    props.clientId())
                .queryParam("redirect_uri", props.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .build().toUriString();
    }

    @Override
    @Retryable(retryFor = Exception.class, noRetryFor = org.springframework.web.client.HttpClientErrorException.class,
               maxAttempts = 2, backoff = @Backoff(delay = 500))
    public OAuthUserInfo getUserInfo(String code, String state) {
        // 1. 인가 코드 → 액세스 토큰
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type",    "authorization_code");
        form.add("client_id",     props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("redirect_uri",  props.redirectUri());
        form.add("code",          code);

        KakaoTokenResponse tokenResp = authClient.post()
                .uri("/oauth/token")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(KakaoTokenResponse.class);
        if (tokenResp == null) throw new IllegalStateException("카카오 토큰 응답이 비어있습니다");

        // 2. 사용자 정보 조회
        KakaoUserResponse userResp = apiClient.get()
                .uri("/v2/user/me")
                .header("Authorization", "Bearer " + tokenResp.accessToken())
                .retrieve()
                .body(KakaoUserResponse.class);
        if (userResp == null) throw new IllegalStateException("카카오 사용자 정보 응답이 비어있습니다");

        String email    = userResp.kakaoAccount() != null ? userResp.kakaoAccount().email() : null;
        String nickname = (userResp.kakaoAccount() != null && userResp.kakaoAccount().profile() != null)
                ? userResp.kakaoAccount().profile().nickname() : null;

        log.debug("kakao getUserInfo: providerId={}", userResp.id());
        return new OAuthUserInfo(String.valueOf(userResp.id()), email, nickname);
    }

    // ── Kakao API 응답 내부 record ──────────────────────────────────────────
    private record KakaoTokenResponse(@JsonProperty("access_token") String accessToken) {}

    private record KakaoUserResponse(
            Long id,
            @JsonProperty("kakao_account") KakaoAccount kakaoAccount
    ) {
        record KakaoAccount(String email, KakaoProfile profile) {}
        record KakaoProfile(String nickname) {}
    }
}
