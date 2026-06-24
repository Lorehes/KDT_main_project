package com.dartcommons.infrastructure.krx;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.dartcommons.user.services.EmailVerificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] KrxClient.isValidPrice() 경계값 단위 테스트 — 이상치 1단 방어(1원 미만 차단) 회귀 방지.
 * [이유] isValidPrice()는 package-private(Option C) — 동일 패키지에서 직접 호출.
 *       KrxClient 생성자가 HostWhitelist.verify()를 호출하므로 @SpringBootTest 컨텍스트 필요(eval-pnl-integration-tests Spec R3).
 * [사이드 임팩트] 없음. 읽기 전용 메서드 검증. KrxClient 패키지 변경 시 이 파일도 같은 패키지로 이동 필요.
 * [수정 시 고려사항] isValidPrice() 임계(현재 1원) 변경 시 아래 케이스 경계값 수치 같이 수정.
 *                  ANOMALY_THRESHOLD(±50%, KrxPriceSyncJob)와 별개로 관리 — 각각의 상수를 독립 수정.
 */
@SpringBootTest
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
class KrxClientTest {

    @MockitoBean DisclosurePollingJob      pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;
    @MockitoBean EmailVerificationService  emailVerificationService;

    @Autowired KrxClient krxClient;

    @Test
    @DisplayName("isValidPrice — null → false")
    void isValidPrice_null_returnsFalse() {
        assertThat(krxClient.isValidPrice(null)).isFalse();
    }

    @Test
    @DisplayName("isValidPrice — 0(ZERO) → false")
    void isValidPrice_zero_returnsFalse() {
        assertThat(krxClient.isValidPrice(BigDecimal.ZERO)).isFalse();
    }

    @Test
    @DisplayName("isValidPrice — 음수(-1) → false")
    void isValidPrice_negative_returnsFalse() {
        assertThat(krxClient.isValidPrice(new BigDecimal("-1"))).isFalse();
    }

    @Test
    @DisplayName("isValidPrice — 0.99(1원 미만) → false")
    void isValidPrice_belowOne_returnsFalse() {
        assertThat(krxClient.isValidPrice(new BigDecimal("0.99"))).isFalse();
    }

    @Test
    @DisplayName("isValidPrice — 1(최저 유효가) → true")
    void isValidPrice_exactlyOne_returnsTrue() {
        assertThat(krxClient.isValidPrice(BigDecimal.ONE)).isTrue();
    }

    @Test
    @DisplayName("isValidPrice — 60000(정상 주가) → true")
    void isValidPrice_normalPrice_returnsTrue() {
        assertThat(krxClient.isValidPrice(new BigDecimal("60000"))).isTrue();
    }
}
