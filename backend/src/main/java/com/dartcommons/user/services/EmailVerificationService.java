package com.dartcommons.user.services;

import com.dartcommons.infrastructure.mail.MailNotificationClient;
import com.dartcommons.user.repositories.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * [목적] 이메일 OTP 발송·검증·rate limit + 검증 완료 마커 관리.
 *       가입 전 단계(pre-auth)에서 이메일 소유를 확인하고 verifiedEmailCache에 마커를 등록한다.
 *       AuthService.signup()이 isEmailVerified()로 마커를 확인해 미검증 가입 차단.
 * [이유] 이메일 OTP는 DB 저장 불필요 — Caffeine 인메모리 캐시(5분 TTL)로 경량 처리.
 *       rate limit도 Caffeine으로 메모리 내 관리 — MVP 단일 인스턴스 가정(다중 인스턴스는 Redis 전환 필요).
 *       PhoneVerificationService와 동일 패턴 적용으로 코드 일관성 확보.
 * [사이드 임팩트] verifiedEmailCache 만료(10분) 전에 signup()을 호출해야 함 — 만료 후 EMAIL_NOT_VERIFIED 422.
 *               OTP 코드는 로그에 절대 평문 출력 금지 — 이메일 주소도 마스킹(앞 3자+***).
 *               MailNotificationClient.send() 실패 시 캐시 무효화 후 MailSendException → GlobalExceptionHandler 500.
 * [수정 시 고려사항] 다중 인스턴스(k8s) 전환 시 Caffeine → Redis + Lua script rate limit으로 교체.
 *                  brute-force 차단 횟수(5회) 변경 시 OtpEntry 검증 로직과 테스트 동기화 필요.
 *                  이메일 템플릿 고도화 시 Thymeleaf TemplateEngine 도입 + MailNotificationClient.sendHtml() 분리.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final String OTP_SUBJECT = "[DartCommons] 이메일 인증 코드";

    private final MailNotificationClient mailClient;
    private final UserRepository         userRepository;
    private final SecureRandom           secureRandom = new SecureRandom();

    // (email → OtpEntry), 5분 TTL — 코드 + 시도 횟수 묶음
    private final Cache<String, OtpEntry> otpCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();

    // (email → Boolean.TRUE), 10분 TTL — 이메일 소유 확인 완료 마커
    private final Cache<String, Boolean> verifiedEmailCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();

    // (email → 1분 내 발송 횟수), 1분 TTL — rate limit
    private final Cache<String, AtomicInteger> rateLimitCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();

    public EmailVerificationService(MailNotificationClient mailClient,
                                    UserRepository userRepository) {
        this.mailClient     = mailClient;
        this.userRepository = userRepository;
    }

    /**
     * OTP 발송 — 6자리 코드 생성 후 이메일로 전송.
     * 이미 가입된 이메일이면 409. rate limit(1분 1회) 초과 시 429.
     * 발송 실패 시 캐시 무효화 후 예외 재전파.
     */
    public void sendOtp(String email) {
        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE");
        }
        AtomicInteger count = rateLimitCache.get(email, k -> new AtomicInteger(0));
        if (count.incrementAndGet() > 1) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED");
        }
        String code = generateOtp();
        otpCache.put(email, new OtpEntry(code));
        try {
            mailClient.send(email, OTP_SUBJECT, buildBody(code));
        } catch (Exception e) {
            otpCache.invalidate(email);
            count.decrementAndGet();  // 발송 실패 시 rate counter 복원 — 재시도 허용
            log.warn("Email OTP send failed: email={}*** error={}", email.substring(0, Math.min(3, email.length())), e.getMessage());
            throw e;
        }
        log.info("Email OTP sent: email={}***", email.substring(0, Math.min(3, email.length())));
    }

    /**
     * OTP 검증 — 캐시 매칭 후 verifiedEmailCache에 마커 등록.
     * 캐시 미존재(만료) 시 410, 5회 초과 시 429(캐시 무효화), 코드 불일치 시 400.
     */
    public void verifyOtp(String email, String code) {
        OtpEntry entry = otpCache.getIfPresent(email);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.GONE, "OTP_EXPIRED");
        }
        if (entry.attempts().incrementAndGet() > 5) {
            otpCache.invalidate(email);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED");
        }
        if (!entry.code().equals(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_OTP");
        }
        otpCache.invalidate(email);
        verifiedEmailCache.put(email, Boolean.TRUE);
        log.info("Email OTP verified: email={}***", email.substring(0, Math.min(3, email.length())));
    }

    /** 이메일 소유 확인 여부 — AuthService.signup() 가드에서 사용. */
    public boolean isEmailVerified(String email) {
        return verifiedEmailCache.getIfPresent(email) != null;
    }

    private String generateOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private String buildBody(String code) {
        return "인증 코드: " + code + " (5분 이내 입력)\n\n"
                + "본 메일을 요청하지 않으셨다면 무시하세요.\n공시레이더 드림";
    }

    /** OTP 캐시 엔트리 — 코드와 시도 횟수를 묶어 brute-force 5회 차단에 사용. */
    private record OtpEntry(String code, AtomicInteger attempts) {
        OtpEntry(String code) {
            this(code, new AtomicInteger(0));
        }
    }
}
