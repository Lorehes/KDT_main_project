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
 *       handleResponseStatus()에서 HTTP status → code 매핑 + message(=reason) 명시로 FE와 계약 일치.
 * [사이드 임팩트] spring.mvc.problemdetails.enabled=true 설정과 충돌 없음 — @ExceptionHandler가 우선.
 *               ResponseStatusException의 reason이 null이면 HTTP status 기본 문구로 대체.
 * [수정 시 고려사항] 도메인별 커스텀 예외 추가 시 이 클래스에 @ExceptionHandler 확장.
 *                  에러 응답에 트레이스 ID(Correlation-ID) 포함은 MDC + RequestFilter에서 처리.
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
            case 409 -> "DUPLICATE_RESOURCE";
            case 422 -> "BUSINESS_RULE_VIOLATION";
            case 403 -> "FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 401 -> "UNAUTHORIZED";
            case 400 -> "VALIDATION_ERROR";
            default  -> "ERROR";
        };
        String reason;
        if (ex.getReason() != null) {
            reason = ex.getReason();
        } else {
            HttpStatus httpStatus = HttpStatus.resolve(statusCode.value());
            reason = (httpStatus != null) ? httpStatus.getReasonPhrase() : "오류가 발생했습니다";
        }

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(statusCode, reason);
        pd.setProperty("code",    code);
        pd.setProperty("message", reason);
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
