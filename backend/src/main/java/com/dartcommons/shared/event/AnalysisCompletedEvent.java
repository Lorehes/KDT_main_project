package com.dartcommons.shared.event;

import com.dartcommons.shared.enums.Sentiment;

import java.math.BigDecimal;

/*
 * [목적] analysis 도메인이 Stage 2~5 1건 완료 시 발행 — notification 도메인이 구독해 발송 트리거.
 *       analysisId 외에 알림 필터링에 필요한 최소 메타(sentiment/confidence/isWithheld) 동봉.
 * [이유] feature_structure §1.2: analysis→notification 직접 import 금지. 이벤트로 결합도 해소.
 *       notification 측에서 confidence/withheld 기반 발송 자격 1차 필터링 가능 — DB 재조회 절감.
 * [사이드 임팩트] 본 Spec wave 1에는 소비자 없음(Spring이 무해 무시). M3 notification Spec 후 구독.
 *               페이로드 확장 시 신중 — 필드 늘리면 이벤트 도메인 누수 위험. 소비자가 DB로 보완 권장.
 * [수정 시 고려사항] disclosureId도 함께 — 알림 메시지 빌더가 disclosure 정보 조회에 필요.
 *                  stage 도달 단계는 Stage 3+ 후속 도입 시 추가(현재 본 Spec=2).
 */
public record AnalysisCompletedEvent(
        Long analysisId,
        Long disclosureId,
        Sentiment sentiment,
        BigDecimal confidence,
        boolean withheld
) {
}
