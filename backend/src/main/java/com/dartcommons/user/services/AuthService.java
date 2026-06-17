package com.dartcommons.user.services;

import com.dartcommons.infrastructure.oauth.OAuthProviderClient;
import com.dartcommons.infrastructure.oauth.OAuthUserInfo;
import com.dartcommons.shared.config.JwtProperties;
import com.dartcommons.shared.security.JwtTokenProvider;
import com.dartcommons.user.dto.AuthResponse;
import com.dartcommons.user.dto.LoginRequest;
import com.dartcommons.user.dto.OAuthUrlResponse;
import com.dartcommons.user.dto.SignupRequest;
import com.dartcommons.user.entities.RefreshTokenEntity;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.RefreshTokenRepository;
import com.dartcommons.user.repositories.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
 * [목적] 이메일 + OAuth 인증 핵심 서비스 — signup / login / refresh(rotation) / logout / force-logout / OAuth 콜백.
 * [이유] JWT access token(HMAC-SHA256, 30분) + refresh token(SHA-256 해시 DB 저장, 14일) 전략.
 *       refresh rotation: 갱신마다 기존 해시 삭제 + 신규 발급 → 토큰 재사용 방지.
 *       "인증 실패" 단일 메시지: 이메일 존재 여부를 노출하지 않아 계정 열거 공격 차단.
 *       OAuth state CSRF: Caffeine 캐시(TTL 5분)로 state UUID 저장, 콜백에서 대조 후 삭제.
 *       OAuth 신규 가입은 계정만 생성(동의 보류) + is_new_user=true 반환 → FE가 /signup/terms?oauth=true 로 유도.
 *       동의는 사용자가 직접 화면에서 수락 후 POST /users/me/oauth-consent 별도 호출(UserController 처리).
 * [사이드 임팩트] 이메일 signup은 ConsentService.recordSignupConsents()와 동일 트랜잭션 — 롤백 시 동의 이력도 함께 롤백.
 *               OAuth autoSignup은 계정 생성만 수행 — consent_logs 없는 상태로 남을 수 있음.
 *               oauthCallback에서 기존 사용자 is_new_user 판단 = onboarding_completed_at NULL 여부(V10).
 *               hasRequiredConsents() 기반 판단은 약관만 체크 → phone/profile/complete 미완료도 returning user로 처리하는 버그.
 *               onboarding_completed_at 전환으로 /signup/complete 도달 전까지는 is_new_user=true 반환.
 *               OAuth 콜백에서 동일 이메일 기존 계정 존재 시 409(수동 이메일 로그인 유도).
 *               refresh 실패(만료/미존재) 시 해당 hash 삭제 없이 401 반환.
 *               logout은 access token을 무효화하지 않음 — 만료(30분) 대기.
 * [수정 시 고려사항] OAuth 계정 연동(이메일 충돌 시 자동 연결) 허용 정책 전환 시 oauthCallback의 충돌 처리 로직 수정.
 *                  로그인 시도 횟수 제한(rate-limit) 추가 시 AuthController AOP/Bucket4j 레이어.
 *                  다중 기기 로그인은 user_id당 refresh_tokens 복수 허용(현재 구조 지원).
 *                  온보딩 미완료 계정 정리가 필요하면 배치로 onboarding_completed_at IS NULL + created_at 경과 기준 삭제.
 */
@Service
@Transactional
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // Naver state CSRF 검증용 캐시 — TTL 5분, 최대 10,000 state UUID 보관
    private final Cache<String, Boolean> oauthStateCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    private final UserRepository          userRepository;
    private final RefreshTokenRepository  refreshTokenRepository;
    private final JwtTokenProvider        jwtTokenProvider;
    private final JwtProperties           jwtProperties;
    private final PasswordEncoder         passwordEncoder;
    private final ConsentService          consentService;
    private final EmailVerificationService emailVerificationService;
    private final Map<String, OAuthProviderClient> oauthProviders;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties,
                       PasswordEncoder passwordEncoder,
                       ConsentService consentService,
                       EmailVerificationService emailVerificationService,
                       List<OAuthProviderClient> oauthClients) {
        this.userRepository           = userRepository;
        this.refreshTokenRepository   = refreshTokenRepository;
        this.jwtTokenProvider         = jwtTokenProvider;
        this.jwtProperties            = jwtProperties;
        this.passwordEncoder          = passwordEncoder;
        this.consentService           = consentService;
        this.emailVerificationService = emailVerificationService;
        // Spring이 OAuthProviderClient 구현체(Kakao/Google/Naver) 전부를 List로 주입 → provider 이름으로 인덱싱
        this.oauthProviders = oauthClients.stream()
                .collect(Collectors.toUnmodifiableMap(OAuthProviderClient::getProviderName, Function.identity()));
    }

    // ── 이메일 인증 ────────────────────────────────────────────────────────

    /** 이메일 회원가입. 이메일 중복 시 409. 이메일 미검증 시 422. 동의 이력 동일 트랜잭션 INSERT. */
    public AuthResponse signup(SignupRequest request) {
        // R5: 이메일 OTP 검증 완료 마커 확인 — 미검증 가입 차단
        if (!emailVerificationService.isEmailVerified(request.email())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_NOT_VERIFIED");
        }
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이메일이 이미 사용 중입니다");
        }

        OffsetDateTime now = OffsetDateTime.now();
        UserEntity user = UserEntity.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .nickname(request.nickname())
                .termsAgreedAt(now)
                .privacyAgreedAt(now)
                .marketingAgreedAt(request.marketingAgreed() ? now : null)
                .build();
        userRepository.save(user);

        consentService.recordSignupConsents(
                user.getId(),
                request.termsAgreed(),
                request.privacyAgreed(),
                request.disclaimerAgreed(),
                request.marketingAgreed()
        );

        log.info("signup: userId={}", user.getId());
        return issueTokenPair(user);
    }

    /** 이메일 로그인. 계정 미존재 또는 비밀번호 불일치 시 동일한 401("인증 실패") 반환. */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByEmailAndDeletedAtIsNull(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 실패"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 실패");
        }

        log.info("login: userId={}", user.getId());
        return issueTokenPair(user);
    }

    /**
     * refresh token rotation — 기존 hash 삭제 + 신규 토큰 쌍 발급.
     * 토큰 미존재·만료 시 401. rotation 후 기존 토큰은 즉시 무효.
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        String hash = jwtTokenProvider.hashRefreshToken(rawRefreshToken);
        RefreshTokenEntity stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh token"));

        if (stored.isExpired()) {
            refreshTokenRepository.deleteByTokenHash(hash);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "만료된 refresh token");
        }

        UserEntity user = userRepository.findByIdAndDeletedAtIsNull(stored.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 실패"));

        refreshTokenRepository.deleteByTokenHash(hash);
        return issueTokenPair(user);
    }

    /** 로그아웃 — refresh token hash DB 삭제. access token은 만료 대기(30분). */
    @Transactional
    public void logout(String rawRefreshToken) {
        String hash = jwtTokenProvider.hashRefreshToken(rawRefreshToken);
        refreshTokenRepository.deleteByTokenHash(hash);
    }

    /** 강제 로그아웃 — 해당 사용자의 모든 refresh token 삭제(전 기기 로그아웃). */
    @Transactional
    public void forceLogout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
        log.info("force-logout: userId={}", userId);
    }

    // ── OAuth 인증 ─────────────────────────────────────────────────────────

    /**
     * OAuth 인가 URL 생성 — state UUID 생성 후 Caffeine 캐시(TTL 5분)에 저장.
     * 지원하지 않는 provider 요청 시 400 반환.
     */
    public OAuthUrlResponse getOAuthAuthorizationUrl(String provider) {
        OAuthProviderClient client = resolveProvider(provider);
        String state = UUID.randomUUID().toString();
        oauthStateCache.put(state, Boolean.TRUE);
        String url = client.buildAuthorizationUrl(state);
        log.debug("oauth url generated: provider={}", provider);
        return new OAuthUrlResponse(url, state);
    }

    /**
     * OAuth 콜백 처리 — state 검증 → 사용자 정보 조회 → 로그인 또는 자동 회원가입 → JWT 발급.
     * state 만료/미존재: 400. 이메일 중복(기존 이메일 계정): 409.
     * 이메일 미동의: placeholder({provider}_{providerId}@oauth.placeholder)로 자동 처리.
     */
    @Transactional
    public AuthResponse oauthCallback(String provider, String code, String state) {
        // 1. state CSRF 검증 (Caffeine 캐시 대조 후 삭제)
        if (oauthStateCache.getIfPresent(state) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state가 만료되었거나 올바르지 않습니다");
        }
        oauthStateCache.invalidate(state);

        // 2. 인가 코드 → 사용자 정보 (provider별 HTTP 호출)
        OAuthProviderClient client = resolveProvider(provider);
        OAuthUserInfo userInfo;
        try {
            userInfo = client.getUserInfo(code, state);
        } catch (HttpClientErrorException e) {
            log.warn("oauth getUserInfo 4xx: provider={} status={}", provider, e.getStatusCode());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth 인증에 실패했습니다");
        } catch (Exception e) {
            log.error("oauth getUserInfo error: provider={}", provider, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "OAuth 인증 처리 중 오류가 발생했습니다");
        }

        UserEntity.OAuthProvider oauthProvider = toOAuthProvider(provider);

        // 3. oauth_id로 기존 사용자 조회 (로그인 또는 약관 동의 미완료 재진입)
        java.util.Optional<UserEntity> existing =
                userRepository.findByOauthProviderAndOauthIdAndDeletedAtIsNull(oauthProvider, userInfo.providerId());
        if (existing.isPresent()) {
            UserEntity user = existing.get();
            // onboarding_completed_at 기준: NOT NULL = 온보딩 완료(기존 사용자), NULL = 온보딩 미완료
            // hasRequiredConsents() 대신 onboarding_completed_at 사용 — 약관 동의 후에도 complete까지 가야 returning user 처리
            if (user.getOnboardingCompletedAt() != null) {
                log.info("oauth login: provider={} userId={}", provider, user.getId());
                return issueTokenPair(user);
            } else {
                log.info("oauth onboarding-incomplete re-entry: provider={} userId={}", provider, user.getId());
                // 온보딩 미완료 재진입 시 기존 refresh_token 전부 삭제 — 반복 진입에 의한 토큰 누적 방지
                refreshTokenRepository.deleteByUserId(user.getId());
                return issueTokenPairAsNew(user);
            }
        }

        // 4. 신규 → 이메일 충돌 체크 후 자동 회원가입
        // 이메일 미동의 케이스는 충돌 체크 스킵 (placeholder 사용)
        boolean hasEmail = userInfo.email() != null && !userInfo.email().isBlank();
        if (hasEmail && userRepository.existsByEmailAndDeletedAtIsNull(userInfo.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "이미 이메일로 가입된 계정입니다. 이메일 로그인을 이용해주세요");
        }
        // DataIntegrityViolationException: 동시 요청이 동일 이메일로 가입 시도 시 UNIQUE 위반 → 409 변환
        try {
            return autoSignup(oauthProvider, userInfo);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "이미 이메일로 가입된 계정입니다. 이메일 로그인을 이용해주세요");
        }
    }

    // ── private helpers ────────────────────────────────────────────────────

    /**
     * OAuth 자동 회원가입 — 계정만 생성, 동의 이력은 기록하지 않음.
     * 동의는 FE /signup/terms?oauth=true 화면에서 사용자가 직접 동의 후 POST /users/me/oauth-consent 호출.
     * is_new_user=true 반환 → FE가 약관 동의 페이지로 리다이렉트.
     */
    private AuthResponse autoSignup(UserEntity.OAuthProvider oauthProvider, OAuthUserInfo userInfo) {
        OffsetDateTime now = OffsetDateTime.now();
        // 이메일 미동의 시 placeholder 생성 — 실 서비스 전환 후 이메일 동의 활성화 시 제거
        String email = (userInfo.email() != null && !userInfo.email().isBlank())
                ? userInfo.email()
                : oauthProvider.name().toLowerCase() + "_" + userInfo.providerId() + "@oauth.placeholder";
        String nickname = (userInfo.nickname() != null && !userInfo.nickname().isBlank())
                ? userInfo.nickname()
                : email.split("@")[0];

        UserEntity user = UserEntity.builder()
                .email(email)
                .oauthProvider(oauthProvider)
                .oauthId(userInfo.providerId())
                .nickname(nickname)
                .termsAgreedAt(now)
                .privacyAgreedAt(now)
                .build();
        userRepository.save(user);

        log.info("oauth auto-signup (consent pending): provider={} userId={}", oauthProvider, user.getId());
        return issueTokenPairAsNew(user);
    }

    private OAuthProviderClient resolveProvider(String provider) {
        OAuthProviderClient client = oauthProviders.get(provider.toLowerCase());
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 OAuth 제공자입니다: " + provider + ". 지원 목록: " + oauthProviders.keySet());
        }
        return client;
    }

    private UserEntity.OAuthProvider toOAuthProvider(String provider) {
        try {
            return UserEntity.OAuthProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            // resolveProvider()가 먼저 실패하므로 실질적으로 도달하지 않는 방어 코드
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 OAuth 제공자입니다: " + provider);
        }
    }

    private AuthResponse issueTokenPair(UserEntity user) {
        return issueTokenPairInternal(user, false);
    }

    private AuthResponse issueTokenPairAsNew(UserEntity user) {
        return issueTokenPairInternal(user, true);
    }

    private AuthResponse issueTokenPairInternal(UserEntity user, boolean isNewUser) {
        String rawRefresh  = jwtTokenProvider.generateRawRefreshToken();
        String tokenHash   = jwtTokenProvider.hashRefreshToken(rawRefresh);
        long   expiresAtMs = jwtTokenProvider.refreshExpiresAtMs();

        RefreshTokenEntity token = RefreshTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(OffsetDateTime.ofInstant(Instant.ofEpochMilli(expiresAtMs), ZoneOffset.UTC))
                .build();
        refreshTokenRepository.save(token);

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getTier().name());

        return isNewUser
                ? AuthResponse.ofNew(accessToken, rawRefresh, jwtProperties.accessTtlMinutes())
                : AuthResponse.of(accessToken, rawRefresh, jwtProperties.accessTtlMinutes());
    }
}
