package com.dartcommons.shared.security;

import com.dartcommons.shared.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/*
 * [목적] JWT access token 발급·파싱·검증 + refresh token (random UUID 기반) 생성·해싱.
 *       access token: HMAC-SHA256 서명, subject=userId, claims에 email/tier 포함.
 *       refresh token: SecureRandom base64, SHA-256 해시를 refresh_tokens 테이블에 저장.
 * [이유] jjwt-api 0.12.x 사용 — Spring Boot 3.x 호환, 단순 API.
 *       refresh token을 JWT로 만들지 않는 이유: 서버측 무효화(logout/rotation)가 필요하므로
 *       DB에 저장해야 함. SHA-256 해시만 저장해 DB 유출 시 원본 토큰 노출 방지.
 * [사이드 임팩트] JWT_SECRET 변경 시 기존 발급 access token 전부 무효화됨. 점진 교체 불가.
 *               AuthService(Wave 2)가 이 컴포넌트에 의존 — 변경 시 AuthService 동반 확인.
 * [수정 시 고려사항] kid(key id) + 멀티 키 지원으로 무중단 key rotation 가능(현재 미구현).
 *                  토큰 블랙리스트(access token 즉시 무효화)가 필요하면 Redis TTL 저장 추가.
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TIER  = "tier";

    private final SecretKey signingKey;
    private final long      accessTtlMs;
    private final long      refreshTtlMs;

    public JwtTokenProvider(JwtProperties props) {
        byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        this.signingKey   = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtlMs  = (long) props.accessTtlMinutes() * 60 * 1000;
        this.refreshTtlMs = (long) props.refreshTtlDays() * 24 * 60 * 60 * 1000;
    }

    /** access token 발급. subject=userId, claims에 email/tier 포함. */
    public String generateAccessToken(Long userId, String email, String tier) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_TIER, tier)
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessTtlMs))
                .signWith(signingKey)
                .compact();
    }

    /** refresh token raw 값 생성 (SecureRandom base64). DB에는 해시만 저장. */
    public String generateRawRefreshToken() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** raw refresh token → SHA-256 hex (DB 저장용). */
    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** refresh token 만료 시각(ms) 계산. */
    public long refreshExpiresAtMs() {
        return System.currentTimeMillis() + refreshTtlMs;
    }

    /** access token 파싱 — 유효하지 않으면 JwtException 계열 throw. */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** userId 추출 (Long). 파싱 실패 시 예외 전파. */
    public Long extractUserId(String token) {
        return Long.parseLong(parseClaims(token).getSubject());
    }

    /** tier 추출. */
    public String extractTier(String token) {
        return parseClaims(token).get(CLAIM_TIER, String.class);
    }

    /** 토큰 만료 여부 확인 (서명 검증 포함). */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
