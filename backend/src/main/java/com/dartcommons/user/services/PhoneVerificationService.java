package com.dartcommons.user.services;

import com.dartcommons.infrastructure.kakao.KakaoAlimtalkClient;
import com.dartcommons.shared.crypto.AesGcmEncryptor;
import com.dartcommons.user.repositories.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * [목적] 휴대폰 OTP 인증 플로우 — 발송(sendVerification)·검증(confirmVerification)·rate limit.
 *       카카오 알림톡 OTP 템플릿으로 6자리 코드를 발송하고, 검증 성공 시 AES-256-GCM으로 번호를 암호화 저장.
 * [이유] phone_number_enc(암호화)와 phone_verified(인증 완료) 두 상태를 동일 트랜잭션에서 원자적으로 갱신.
 *       OTP는 Caffeine 인메모리 캐시(5분 TTL) — DB 저장 없이 경량 처리.
 *       rate limit도 Caffeine으로 메모리 내 관리 — MVP 단일 인스턴스 가정(다중 인스턴스는 Redis 전환 필요).
 * [사이드 임팩트] completePhoneVerification()이 UserEntity를 더티마킹 → JPA 자동 UPDATE.
 *               카카오 알림톡 호출 실패 시 RestClientException → GlobalExceptionHandler가 500 반환.
 *               OTP 엔트리는 인메모리 — 서버 재기동 시 초기화(사용자 재요청 필요).
 * [수정 시 고려사항] 다중 인스턴스(k8s) 전환 시 Caffeine → Redis + Lua script rate limit으로 교체.
 *                  번호 변경 플로우(기존 번호 덮어쓰기)도 이 서비스를 재사용 — phone_verified가 리셋됨.
 *                  카카오 알림톡 채널 미추가 사용자는 발송 실패 → 추후 SMS 폴백 도입 시 이 클래스에 분기.
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);
    private final KakaoAlimtalkClient kakaoClient;
    private final AesGcmEncryptor     encryptor;
    private final UserRepository      userRepository;
    private final SecureRandom        secureRandom = new SecureRandom();

    // (userId → OtpEntry) 캐시 — 5분 TTL, 최대 10만 건
    private final Cache<Long, OtpEntry> otpCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();

    // 1분 1회 rate limit — (userId → 1분 내 발송 횟수)
    private final Cache<Long, AtomicInteger> minuteRateCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();

    // 시간당 5회 rate limit — (userId → 1시간 내 누적 발송 횟수)
    private final Cache<Long, AtomicInteger> hourRateCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(100_000)
            .build();

    public PhoneVerificationService(KakaoAlimtalkClient kakaoClient,
                                    AesGcmEncryptor encryptor,
                                    UserRepository userRepository) {
        this.kakaoClient    = kakaoClient;
        this.encryptor      = encryptor;
        this.userRepository = userRepository;
    }

    /**
     * OTP 발송 — 6자리 코드 생성 후 카카오 알림톡으로 전송. rate limit 초과 시 429.
     * phoneNumber는 하이픈 없는 11자리 숫자 문자열(예: "01012345678").
     */
    public void sendVerification(Long userId, String phoneNumber) {
        checkRateLimit(userId);

        String code = generateOtp();
        otpCache.put(userId, new OtpEntry(phoneNumber, code)); // attempts=0 초기화

        try {
            kakaoClient.sendOtp(phoneNumber, code);
            log.info("Phone OTP sent: userId={}", userId);
        } catch (Exception e) {
            otpCache.invalidate(userId);
            log.warn("Phone OTP send failed: userId={} error={}", userId, e.getMessage());
            throw e;
        }
    }

    /**
     * OTP 검증 + 번호 암호화 저장. 성공 시 phone_verified=true, phone_number_enc 갱신.
     * 만료(캐시 미존재) 시 410, 코드 불일치 시 400, 시도 5회 초과 시 429(캐시 무효화).
     */
    @Transactional
    public void confirmVerification(Long userId, String inputCode) {
        OtpEntry entry = otpCache.getIfPresent(userId);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.GONE, "OTP_EXPIRED");
        }
        if (entry.attempts().incrementAndGet() > 5) {
            otpCache.invalidate(userId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED");
        }
        if (!entry.code().equals(inputCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_OTP");
        }
        otpCache.invalidate(userId);

        userRepository.findByIdAndDeletedAtIsNull(userId).ifPresent(user -> {
            byte[] encryptedPhone = encryptor.encrypt(entry.phoneNumber());
            user.completePhoneVerification(encryptedPhone);
        });
        log.info("Phone OTP confirmed: userId={}", userId);
    }

    private void checkRateLimit(Long userId) {
        // 1분 1회
        AtomicInteger minuteCount = minuteRateCache.get(userId, k -> new AtomicInteger(0));
        if (minuteCount.incrementAndGet() > 1) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED");
        }
        // 시간당 5회
        AtomicInteger hourCount = hourRateCache.get(userId, k -> new AtomicInteger(0));
        if (hourCount.incrementAndGet() > 5) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED");
        }
    }

    private String generateOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    /** OTP 캐시 엔트리 — 발송 대상 번호·코드·시도 횟수를 묶어 관리. */
    private record OtpEntry(String phoneNumber, String code, AtomicInteger attempts) {
        OtpEntry(String phoneNumber, String code) {
            this(phoneNumber, code, new AtomicInteger(0));
        }
    }
}
