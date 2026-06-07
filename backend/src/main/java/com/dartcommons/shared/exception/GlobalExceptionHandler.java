package com.dartcommons.shared.exception;

import com.dartcommons.shared.crypto.AesGcmEncryptor;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/*
 * [목적] 전역 예외 핸들러 — @Valid 실패(400) + @Validated @RequestParam 제약 위반(400) + AES 암복호 실패(500) + RFC 7807 ProblemDetail.
 * [이유] @Valid 실패(MethodArgumentNotValidException)와 @Validated RequestParam 실패(ConstraintViolationException)
 *       모두 동일한 RFC 7807 ProblemDetail 포맷으로 반환해 클라이언트 에러 처리 일관성 확보.
 *       CryptoException은 AES_KEY 변경이나 DB 데이터 손상 시 발생 — 스택 트레이스 내부 로그, 클라이언트에 일반 메시지만 반환.
 * [사이드 임팩트] ResponseStatusException은 Spring이 자동 처리 — 이 핸들러 불필요.
 *               spring.mvc.problemdetails.enabled=true로 일관된 RFC 7807 포맷 보장.
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

    @ExceptionHandler(AesGcmEncryptor.CryptoException.class)
    public ProblemDetail handleCryptoError(AesGcmEncryptor.CryptoException ex) {
        log.error("AES-GCM crypto operation failed", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "데이터 처리 중 오류가 발생했습니다");
        pd.setTitle("서버 오류");
        return pd;
    }
}
