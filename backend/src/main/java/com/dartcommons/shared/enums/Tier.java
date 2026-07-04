package com.dartcommons.shared.enums;

/*
 * [목적] 사용자 구독 티어 — FREE/PRO/PREMIUM. 전체 도메인에서 공유하는 단일 정의.
 * [이유] `UserEntity.Tier`와 `AnalysisResponse.Tier` 이중 정의로 인해 AnalysisQueryService에서
 *       switch 변환 보일러플레이트가 발생했음(CLAUDE.md §3-2 도메인 경계 정리 일환).
 *       shared/enums로 격리해 모든 도메인이 단일 타입을 참조하도록 통합.
 * [사이드 임팩트] UserEntity.tier 필드가 이 타입을 참조 — @Enumerated(EnumType.STRING) + name() 동일
 *               (FREE/PRO/PREMIUM) 유지로 DB 컬럼·JWT authority·JSON 응답 변경 없음.
 *               기존 UserEntity.Tier 참조 코드는 import 교체만으로 마이그레이션 완료.
 * [수정 시 고려사항] ordinal 순서가 권한 강도 순(FREE < PRO < PREMIUM)임에 의존하는 코드 존재
 *                  (SecurityUtils.extractTier에서 max(ordinal) 사용).
 *                  새 티어 추가 시 ordinal 순서를 낮은 권한 → 높은 권한 순으로 유지 필수.
 */
public enum Tier {
    FREE, PRO, PREMIUM
}
