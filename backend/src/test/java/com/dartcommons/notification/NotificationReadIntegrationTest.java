package com.dartcommons.notification;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.entities.NotificationEntity.Channel;
import com.dartcommons.notification.repositories.NotificationRepository;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.UserRepository;
import com.dartcommons.user.services.EmailVerificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/*
 * [목적] 알림 읽음 처리 통합 테스트 — PATCH /{id}/read · PATCH /read-all · GET /unread-count + IDOR 방어.
 * [이유] Testcontainers PostgreSQL로 실 DB 검증(Mock DB 금지 — CLAUDE.md §6-6).
 *       IDOR: 타인의 알림을 읽음 처리 시도 시 403 반환 여부를 HTTP 계층에서 검증.
 * [사이드 임팩트] NotificationEntity를 DB에 직접 저장 — NotificationDispatcher 우회(알림 발송 채널 무관).
 *               BeforeEach에서 전 테이블 삭제 — 각 테스트는 독립 실행.
 * [수정 시 고려사항] WebSocket 도입 후 unread 이벤트 푸시 검증 추가.
 *                  페이지네이션 추가 시 unread-count 범위 테스트 확장.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "dartcommons.dart.api-key=test-key",
        "dartcommons.dart.base-url=http://localhost",
        "dartcommons.krx.api-key=test-key",
        "dartcommons.krx.base-url=http://localhost",
        "dartcommons.admin.username=admin",
        "dartcommons.admin.password=test-admin-password",
        "dartcommons.llm.provider=mock"
})
class NotificationReadIntegrationTest {

    @MockitoBean DisclosurePollingJob      pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;
    @MockitoBean EmailVerificationService  emailVerificationService;

    @Autowired MockMvc               mockMvc;
    @Autowired ObjectMapper          objectMapper;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository        userRepository;
    @Autowired DisclosureRepository  disclosureRepository;
    @Autowired JdbcTemplate          jdbcTemplate;

    @BeforeEach
    void setUp() {
        when(emailVerificationService.isEmailVerified(anyString())).thenReturn(true);
        // consent_logs ON DELETE RESTRICT — notifications·consent_logs·refresh_tokens 먼저 삭제 후 users
        jdbcTemplate.execute("DELETE FROM notifications");
        jdbcTemplate.execute("DELETE FROM consent_logs");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM disclosures");
        jdbcTemplate.execute("DELETE FROM users");
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private String uniqueEmail() {
        return "nr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@test.com";
    }

    private String signupAndGetToken(String email) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("email",            email)
                .put("password",         "Password1!")
                .put("nickname",         "테스터")
                .put("termsAgreed",      true)
                .put("privacyAgreed",    true)
                .put("disclaimerAgreed", true)
                .put("marketingAgreed",  false);

        String resp = mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("access_token").asText();
    }

    private Disclosure createDisclosure(String suffix) {
        return disclosureRepository.save(Disclosure.builder()
                .rceptNo("20260611" + suffix)
                .corpCode("005930")
                .corpName("삼성전자")
                .reportNm("테스트 공시")
                .rceptDt(LocalDate.now())
                .disclosureType("OTHER")
                .collectedAt(OffsetDateTime.now())
                .build());
    }

    private NotificationEntity saveNotification(Long userId, Long disclosureId, boolean isRead) {
        NotificationEntity n = NotificationEntity.builder()
                .userId(userId)
                .disclosureId(disclosureId)
                .channel(Channel.EMAIL)
                .build();
        if (isRead) n.markRead();
        return notificationRepository.save(n);
    }

    private Long getUserId(String email) {
        return userRepository.findByEmailAndDeletedAtIsNull(email)
                .map(UserEntity::getId)
                .orElseThrow();
    }

    // ─── tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /{id}/read — 미읽음 알림 읽음 처리 성공 → 204, is_read=true")
    void markRead_unread_setsIsReadTrue() throws Exception {
        String email = uniqueEmail();
        String token = signupAndGetToken(email);
        Long userId  = getUserId(email);

        NotificationEntity notif = saveNotification(userId, createDisclosure("001").getId(), false);

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", notif.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        NotificationEntity updated = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(updated.isRead()).isTrue();
        assertThat(updated.getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("PATCH /{id}/read — 이미 읽은 알림 재처리 시 204 반환, readAt 변경 없음")
    void markRead_alreadyRead_idempotent() throws Exception {
        String email = uniqueEmail();
        String token = signupAndGetToken(email);
        Long userId  = getUserId(email);

        NotificationEntity notif = saveNotification(userId, createDisclosure("002").getId(), true);
        OffsetDateTime originalReadAt = notif.getReadAt();

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", notif.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        NotificationEntity after = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(after.getReadAt()).isEqualTo(originalReadAt); // DB write 없음
    }

    @Test
    @DisplayName("PATCH /{id}/read IDOR — 타인 알림 읽음 처리 시도 → 403, DB 변경 없음")
    void markRead_anotherUsersNotification_returns403() throws Exception {
        String emailA = uniqueEmail();
        String emailB = uniqueEmail();
        signupAndGetToken(emailA);
        String tokenB = signupAndGetToken(emailB);
        Long userAId  = getUserId(emailA);

        // A의 알림을 B가 읽음 처리 시도
        NotificationEntity notifA = saveNotification(userAId, createDisclosure("003").getId(), false);

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", notifA.getId())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());

        assertThat(notificationRepository.findById(notifA.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    @DisplayName("PATCH /read-all — 전체 읽음 처리 후 미읽음 0건")
    void markAllRead_allBecomesRead() throws Exception {
        String email = uniqueEmail();
        String token = signupAndGetToken(email);
        Long userId  = getUserId(email);

        saveNotification(userId, createDisclosure("004").getId(), false);
        saveNotification(userId, createDisclosure("005").getId(), false);

        mockMvc.perform(patch("/api/v1/notifications/read-all")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(notificationRepository.countByUserIdAndIsReadFalse(userId)).isZero();
    }

    @Test
    @DisplayName("GET /unread-count — 미읽음 2건, 읽음 1건 → count=2")
    void unreadCount_returnsCorrectCount() throws Exception {
        String email = uniqueEmail();
        String token = signupAndGetToken(email);
        Long userId  = getUserId(email);

        saveNotification(userId, createDisclosure("006").getId(), false);
        saveNotification(userId, createDisclosure("007").getId(), false);
        saveNotification(userId, createDisclosure("008").getId(), true);

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }
}
