package com.dartcommons.user.dto;

import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.shared.enums.Sentiment;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/*
 * [목적] GET /api/v1/notifications 알림 이력 응답 DTO — FE Notification 타입과 1:1 대응.
 * [이유] NotificationEntity는 disclosureId·channel·status만 갖고, corp_name·report_nm·sentiment는 Disclosure·AnalysisResult에서 조인.
 *       서비스 계층에서 bulk 조인 후 이 DTO로 변환 — N+1 방지.
 * [사이드 임팩트] disclosure가 삭제된 경우(미발생 설계이나 방어) corp_name/report_nm = null → NON_NULL 직렬화 제외.
 * [수정 시 고려사항] is_read 필드는 BE 미구현 — 추후 notifications.is_read 컬럼 추가 후 포함.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationResponse(
        Long id,
        @JsonProperty("disclosure_id") Long disclosureId,
        @JsonProperty("corp_name")     String corpName,
        @JsonProperty("report_nm")     String reportNm,
        Sentiment sentiment,
        NotificationEntity.Channel channel,
        NotificationEntity.Status  status,
        @JsonProperty("created_at")    String createdAt
) {

    public static NotificationResponse from(NotificationEntity n, Disclosure disclosure, Sentiment sentiment) {
        return new NotificationResponse(
                n.getId(),
                n.getDisclosureId(),
                disclosure != null ? disclosure.getCorpName()  : null,
                disclosure != null ? disclosure.getReportNm()  : null,
                sentiment,
                n.getChannel(),
                n.getStatus(),
                n.getCreatedAt() != null ? n.getCreatedAt().toString() : null
        );
    }
}
