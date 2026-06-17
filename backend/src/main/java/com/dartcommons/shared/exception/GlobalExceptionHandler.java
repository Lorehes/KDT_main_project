package com.dartcommons.shared.exception;

import com.dartcommons.shared.crypto.AesGcmEncryptor;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/*
 * [목적] 전역 예외 핸들러 — @Valid 실패(400) + @Validated @RequestParam 제약 위반(400) + AES 암복호 실패(500) + ResponseStatusException + RFC 7807 ProblemDetail.
 * [이유] Spring 기본 ResponseStatusException 자동 처리는 code·message 필드를 포함하지 않아
 *       FE ApiError 인터페이스({code, message})와 불일치 — FE 에러 분기가 항상 폴백으로 떨어짐.
 *       handleResponseStatus()에서 HTTP status → code 매핑 + 안전한 generic message 반환으로 FE 계약 일치.
 * [사이드 임팩트] spring.mvc.problemdetails.enabled=true 설정과 충돌 없음 — @ExceptionHandler가 우선.
 *               400·401·403·404·429·5xx → HTTP status 기반 generic message(내부 정보 차단).
 *               409·410·422 → reason이 도메인 비즈니스 메시지로 사용 가능하므로 그대로 반환.
 *               단, 이 코드로 ResponseStatusException을 throw할 때 reason에 DB 오류·내부 경로 등 기술 정보를 담는 것은 금지.
 * [수정 시 고려사항] 도메인별 커스텀 예외 추가 시 이 클래스에 @ExceptionHandler 확장.
 *                  에러 응답에 트레이스 ID(Correlation-ID) 포함은 MDC + RequestFilter에서 처리.
 *                  5xx 응답에 reason을 노출해야 할 경우 X-Internal-Error 헤더(개발 환경 한정)로 분리.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("입력 값 오류");
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    String param = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return param + ": " + v.getMessage();
                })
                .collect(Collectors.joining(", "));

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("입력 값 오류");
        return pd;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode statusCode = ex.getStatusCode();
        String code = switch (statusCode.value()) {
            case 400 -> "VALIDATION_ERROR";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "DUPLICATE_RESOURCE";
            case 410 -> "RESOURCE_GONE";
            case 422 -> "BUSINESS_RULE_VIOLATION";
            case 429 -> "TOO_MANY_REQUESTS";
            default  -> statusCode.value() >= 500 ? "INTERNAL_SERVER_ERROR" : "ERROR";
        };
        // reason은 내부 정보(테이블명·경로 등) 포함 가능 — 서버 로그에만 기록, 클라이언트에 노출 금지
        if (ex.getReason() != null) {
            log.warn("ResponseStatusException [{}]: {}", statusCode.value(), ex.getReason());
        }
        String safeMessage = switch (statusCode.value()) {
            case 400 -> "요청 값이 올바르지 않습니다";
            case 401 -> "인증이 필요합니다";
            case 403 -> "접근 권한이 없습니다";
            case 404 -> "요청한 리소스를 찾을 수 없습니다";
            case 409 -> ex.getReason() != null ? ex.getReason() : "이미 존재하는 리소스입니다";
            case 410 -> ex.getReason() != null ? ex.getReason() : "리소스가 더 이상 존재하지 않습니다";
            case 422 -> ex.getReason() != null ? ex.getReason() : "비즈니스 규칙 위반입니다";
            case 429 -> "요청이 너무 많습니다. 잠시 후 다시 시도해주세요";
            default  -> statusCode.value() >= 500 ? "서버 오류가 발생했습니다" : "오류가 발생했습니다";
        };

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(statusCode, safeMessage);
        pd.setProperty("code",    code);
        pd.setProperty("message", safeMessage);
        return pd;
    }

    @ExceptionHandler(AesGcmEncryptor.CryptoException.class)
    public ProblemDetail handleCryptoError(AesGcmEncryptor.CryptoException ex) {
        log.error("AES-GCM crypto operation failed", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "데이터 처리 중 오류가 발생했습니다");
        pd.setTitle("서버 오류");
        return pd;
    }
}
