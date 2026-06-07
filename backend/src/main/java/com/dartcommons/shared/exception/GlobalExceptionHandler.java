package com.dartcommons.shared.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/*
 * [목적] 전역 예외 핸들러 — @Valid 실패(400) + 표준 에러 응답(RFC 7807 ProblemDetail).
 * [이유] @Valid 실패 시 Spring 기본 응답은 사용자에게 노출하기 어려운 내부 포맷.
 *       필드별 메시지를 수집해 클라이언트 친화적인 detail로 변환.
 * [사이드 임팩트] ResponseStatusException은 Spring이 자동 처리 — 이 핸들러 불필요.
 *               spring.mvc.problemdetails.enabled=true로 일관된 RFC 7807 포맷 보장.
 * [수정 시 고려사항] 도메인별 커스텀 예외 추가 시 이 클래스에 @ExceptionHandler 확장.
 *                  에러 응답에 트레이스 ID(Correlation-ID) 포함은 MDC + RequestFilter에서 처리.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("입력 값 오류");
        return pd;
    }
}
