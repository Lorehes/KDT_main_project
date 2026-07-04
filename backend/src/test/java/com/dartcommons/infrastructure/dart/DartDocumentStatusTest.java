package com.dartcommons.infrastructure.dart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] DART 비-zip 에러 응답의 status 파싱 + 일시/영구 분류 검증.
 * [이유] content-text-charset-mojibake 재수집 중 status 020("사용한도 초과")을 영구 실패로 저장 →
 *       content_fetched_at 마킹 → 재수집 불가로 21k건 빈 값. 020/800/900을 일시적으로 분류해 재시도 보장.
 * [사이드 임팩트] 없음(순수 단위). DartDocumentClient의 package-private static 메서드 직접 검증.
 * [수정 시 고려사항] DART가 새 일시적 status를 도입하면 TRANSIENT_DART_STATUS + 본 테스트 동기 보강.
 */
class DartDocumentStatusTest {

    @Test
    @DisplayName("실제 020 응답(사용한도 초과) → status 020 추출 + 일시적 분류")
    void extractsAndClassifies020() {
        String real = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<result><status>020</status><message>사용한도를 초과하였습니다.</message></result>";
        assertThat(DartDocumentClient.extractDartStatus(real)).isEqualTo("020");
        assertThat(DartDocumentClient.isTransientDartStatus("020")).isTrue();
    }

    @Test
    @DisplayName("013(조회 데이터 없음) → 추출되지만 영구(일시적 아님)")
    void classifies013AsPermanent() {
        String noData = "<result><status>013</status><message>조회된 데이타가 없습니다.</message></result>";
        assertThat(DartDocumentClient.extractDartStatus(noData)).isEqualTo("013");
        assertThat(DartDocumentClient.isTransientDartStatus("013")).isFalse();
    }

    @Test
    @DisplayName("JSON 형식 에러 응답도 status 파싱")
    void extractsFromJson() {
        String json = "{\"status\":\"800\",\"message\":\"시스템 점검 중\"}";
        assertThat(DartDocumentClient.extractDartStatus(json)).isEqualTo("800");
        assertThat(DartDocumentClient.isTransientDartStatus("800")).isTrue();
    }

    @Test
    @DisplayName("status 없는 preview → 빈 문자열 + 영구")
    void noStatusFound() {
        assertThat(DartDocumentClient.extractDartStatus("garbage preview")).isEmpty();
        assertThat(DartDocumentClient.isTransientDartStatus("")).isFalse();
    }
}
