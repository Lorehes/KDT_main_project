package com.dartcommons.user.services;

import com.dartcommons.shared.config.JwtProperties;
import com.dartcommons.shared.security.JwtTokenProvider;
import com.dartcommons.user.dto.AuthResponse;
import com.dartcommons.user.dto.LoginRequest;
import com.dartcommons.user.dto.SignupRequest;
import com.dartcommons.user.entities.RefreshTokenEntity;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.RefreshTokenRepository;
import com.dartcommons.user.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/*
 * [목적] 이메일 인증 핵심 서비스 — signup / login / refresh(rotation) / logout / force-logout.
 * [이유] JWT access token(HMAC-SHA256, 30분) + refresh token(SHA-256 해시 DB 저장, 14일) 전략.
 *       refresh rotation: 갱신마다 기존 해시 삭제 + 신규 발급 → 토큰 재사용 방지.
 *       "인증 실패" 단일 메시지: 이메일 존재 여부를 노출하지 않아 계정 열거 공격 차단.
 * [사이드 임팩트] signup은 ConsentService.recordSignupConsents()와 동일 트랜잭션 — 롤백 시 동의 이력도 함께 롤백.
 *               refresh 실패(만료/미존재) 시 해당 hash 삭제 없이 401 반환.
 *               logout은 access token을 무효화하지 않음 — 만료(30분) 대기. 즉시 무효화 필요 시 Redis 블랙리스트 도입.
 * [수정 시 고려사항] 로그인 시도 횟수 제한(rate-limit) 추가 시 AuthController AOP/Bucket4j 레이어.
 *                  다중 기기 로그인은 user_id당 refresh_tokens 복수 허용(현재 구조 지원).
 *                  OAuth 콜백은 Wave 4에서 oauthLogin(provider, oauthId, email, nickname) 메서드 추가.
 */
@Service
@Transactional
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository        userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider      jwtTokenProvider;
    private final JwtProperties         jwtProperties;
    private final PasswordEncoder       passwordEncoder;
    private final ConsentService        consentService;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties,
                       PasswordEncoder passwordEncoder,
                       ConsentService consentService) {
        this.userRepository        = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider      = jwtTokenProvider;
        this.jwtProperties         = jwtProperties;
        this.passwordEncoder       = passwordEncoder;
        this.consentService        = consentService;
    }

    /** 이메일 회원가입. 이메일 중복 시 409. 동의 이력 동일 트랜잭션 INSERT. */
    public AuthResponse signup(SignupRequest request) {
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

    private AuthResponse issueTokenPair(UserEntity user) {
        String rawRefresh = jwtTokenProvider.generateRawRefreshToken();
        String tokenHash  = jwtTokenProvider.hashRefreshToken(rawRefresh);
        long   expiresAtMs = jwtTokenProvider.refreshExpiresAtMs();

        RefreshTokenEntity token = RefreshTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(OffsetDateTime.ofInstant(Instant.ofEpochMilli(expiresAtMs), ZoneOffset.UTC))
                .build();
        refreshTokenRepository.save(token);

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getTier().name());

        return AuthResponse.of(accessToken, rawRefresh, jwtProperties.accessTtlMinutes());
    }
}
