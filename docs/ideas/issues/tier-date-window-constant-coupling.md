---
type: issue
status: Closed
created: 2026-07-05
updated: 2026-07-05
resolved: 2026-07-05
source: dc-review-code (portfolios-recent-disclosures-5d, maintainability-reviewer P2)
priority: P3
---

> **상태**: Closed (→ Spec) — 2026-07-05 `docs/specs/Draft/tier-policy-config-api.md` 승격.
> 다음 단계(Approved): `/dc-implement tier-policy-config-api`

# 티어 날짜 창 상수가 BE/FE 3곳에 리터럴로 중복 — 단일 소스 없음

## 현상

Free 티어 공시 조회 날짜 창 값이 컴파일/런타임 강제 없이 3곳에 리터럴로 흩어져 있고, 결합은 오직 헤더 주석으로만 문서화된다:

| 위치 | 상수 | 값 |
|------|------|----|
| `backend/.../disclosure/services/DisclosureQueryService.java:57` | `FREE_WINDOW_DAYS` | 5 |
| `frontend/src/app/(app)/portfolios/page.tsx:32` | `RECENT_DISCLOSURE_DAYS` | 5 |
| `frontend/src/app/(app)/dashboard/page.tsx:30` | `RECENT_FEED_DAYS` | 3 |

- 불변식: `RECENT_FEED_DAYS(3) ≤ RECENT_DISCLOSURE_DAYS(5) ≤ FREE_WINDOW_DAYS(5)`.
- BE가 `FREE_WINDOW_DAYS`를 축소(예: 5→3)하면 FE 두 상수를 사람이 함께 고치지 않는 한 **Free 사용자에게 라벨-실데이터 불일치**가 조용히 발생(주석이 스스로 경고하는 실패 모드). 이를 잡아줄 자동 테스트나 API 노출 값도 없다.

## 기대 동작 / 제안

- **단기**: BE·FE에 `FREE_WINDOW_DAYS ≥ 각 FE 창` 회귀 어서션(통합/E2E)을 추가해 상수 이탈 시 빌드가 깨지게 한다.
- **장기**: 티어 정책(날짜 창·건수 상한)을 백엔드 단일 설정으로 옮기고 `/api/v1/tiers`(또는 config 응답)로 노출 → FE는 API 응답에서만 파생, 하드코딩 제거. → **별도 Spec 승격 후보** (`/dc-tech-review`).

## 맥락
- 출처: dc-review-code (portfolios-recent-disclosures-5d, maintainability-reviewer P2)
- 날짜: 2026-07-05
- 관련 레이어: Disclosure(BE) / Frontend
- 참고: [[portfolios-recent-disclosures-5d]] Done Spec "구현 후 Follow-up" 섹션에도 기록됨. 현재는 3곳뿐이라 즉각 위험은 낮으나, 티어 정책이 추가될수록 확산.
