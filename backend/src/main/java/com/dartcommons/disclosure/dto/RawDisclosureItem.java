package com.dartcommons.disclosure.dto;

/*
 * [목적] disclosure 도메인이 외부(DART) 응답 스키마와 무관하게 다루는 원공시 메타 DTO.
 *       Stage 1 룰 추출(엔티티 빌드) 입력. DART/KRX/다른 출처 어디서 왔는지 도메인은 모름.
 * [이유] 이전엔 DisclosureCollectionService/BackfillService가 DartListResponse.Item을 직접 import해
 *       infrastructure.dart.dto 스키마 변동성에 도메인이 노출됨(CLAUDE.md §3-2 도메인→infra 의존 위반).
 *       infrastructure가 변환 책임을 가지도록 중간 DTO 도입 — DART 응답 필드 변동 시 변환 로직만 수정.
 * [사이드 임팩트] DART 응답 필드 추가 시 본 record는 변경 안 함(infrastructure 변환에서 흡수).
 *               record 필드가 추가되어야 하면 도메인이 의도적으로 새 정보를 요구한다는 신호.
 * [수정 시 고려사항] 원본 보존 정책(CLAUDE.md §4) 그대로 — corpName/reportNm은 변형 금지.
 *                  rceptDt는 LocalDate가 아닌 String("YYYYMMDD") — 파싱은 서비스 계층(역사적 호환).
 *                  Stage 1 본문 추출(content_text)은 후속 Spec 도입 시 별도 record로 확장.
 */
public record RawDisclosureItem(
        String rceptNo,
        String corpCode,
        /** 비상장은 null. infrastructure 변환에서 공백/null 정규화 후 전달. */
        String stockCode,
        String corpName,
        String reportNm,
        /** "YYYYMMDD" 형식. */
        String rceptDt
) {
}
