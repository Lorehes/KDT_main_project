package com.dartcommons.infrastructure.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/*
 * [목적] JavaMailSender(Spring Boot 자동 구성)를 래핑해 알림 이메일 단건 발송.
 *       카카오 알림톡 발송 불가 시 폴백 채널로 사용.
 * [이유] infrastructure/mail로 격리해 notification 도메인이 JavaMail 세부사항에 의존하지 않도록 함
 *       (CLAUDE.md §3-2, feature_structure §6).
 *       JavaMailSender는 spring-boot-starter-mail + spring.mail.* 프로퍼티로 자동 구성됨.
 * [사이드 임팩트] spring.mail.host 미설정 시 JavaMailSender 자동 구성 실패 → 부팅 실패.
 *               테스트 application.yml에 spring.mail.host=localhost(더미) 추가 필수.
 *               SMTP 인증 실패 시 MailException throw → NotificationDispatcher가 FAILED 기록.
 * [수정 시 고려사항] HTML 이메일 전환 시 helper.setText(body, true) + 템플릿(Thymeleaf 등) 도입.
 *                  대량 발송 시 비동기 배치(Async MimeMessagePreparator) 전환 고려.
 */
@Component
public class MailNotificationClient {

    private static final Logger log = LoggerFactory.getLogger(MailNotificationClient.class);

    private final JavaMailSender mailSender;
    private final MailNotificationProperties props;

    public MailNotificationClient(JavaMailSender mailSender, MailNotificationProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    /**
     * 이메일 단건 발송. 성공 시 true, 실패 시 MailException throw.
     * 발신자 주소는 dartcommons.mail.from 프로퍼티 고정.
     */
    public boolean send(String toEmail, String subject, String body) {
        if (toEmail == null || toEmail.isBlank()) {
            throw new IllegalArgumentException("toEmail must not be blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (log.isDebugEnabled()) {
            log.debug("Mail send attempt: to={}", maskEmail(toEmail));
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(props.from());
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body != null ? body : "", false);
            mailSender.send(message);
            return true;
        } catch (MessagingException e) {
            throw new org.springframework.mail.MailSendException(
                    "Failed to prepare mail to " + maskEmail(toEmail), e);
        }
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "****";
        String[] parts = email.split("@");
        return (parts[0].length() > 2 ? parts[0].substring(0, 2) : parts[0]) + "***@" + parts[1];
    }
}
