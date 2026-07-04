package com.dartcommons.infrastructure.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * [목적] application.yml dartcommons.mail.* (발신자 주소 등)를 타입 안전 record로 바인딩.
 * [이유] spring.mail.* 은 Spring Boot 자동 구성이 관리. 발신자 주소는 프로젝트 설정값이라 분리.
 * [사이드 임팩트] MailNotificationClient가 이 properties에 의존 — 미설정 시 구동 실패.
 * [수정 시 고려사항] from 주소 변경 시 SPF/DKIM 레코드도 함께 갱신 필요.
 */
@ConfigurationProperties("dartcommons.mail")
@Validated
public record MailNotificationProperties(
        @NotBlank @Email String from
) {
}
