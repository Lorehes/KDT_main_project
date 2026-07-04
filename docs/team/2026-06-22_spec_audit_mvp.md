---
type: doc
status: active
created: 2026-06-22
updated: 2026-06-22
audience: 팀원 전체 (기획·개발·디자인·운영)
---

# Spec 감사 리포트 — MVP 런치 전 필요/불필요 분류 (2026-06-22)

> **목적**: 2026-07-03 MVP 소프트 런치 전 18개 Spec(Draft 15 + Approved 3)을 전수 검토해
> 필수·권장·이후 3등급으로 분류하고, 판단 근거를 명시한다.
>
> **anchor 문서**: [[milestone-2026-mvp-launch]] Go/No-Go 체크리스트 7개 항목
> **관련 규칙**: [[CLAUDE]] §6-5 WCAG 2.1 AA · §7 절대 금지 · §6-3 Flyway

---

## 판단 프레임워크

분류 기준으로 3개 축을 사용했다.

1. **Blocking** — P0 버그 / 법적 필수 / Go/No-Go 체크리스트 직결 항목
2. **Important** — 비즈니스 핵심 UX / 마일스톤 명시 필수 / WCAG 2.1 AA 의무
3. **Deferrable** — 마일스톤 "선택" 명시 / 외부 의존 / 개발 도구 / MVP 이후 로드맵

---

## 전체 요약 테이블

| 분류 | Spec | 현재 상태 | 마일스톤 연결 |
|------|------|-----------|--------------|
|  **필수**| `be-api-alignment-mvp-r1` | Draft | Go/No-Go 알림·공시 조회 |
| ✅ **필수** | `oauth-consent-enforcement` | Draft | Go/No-Go 법적 DISCLAIMER |
| ✅ **필수** | `portfolio-search-keyboard-nav` | Draft | WCAG 2.1 AA (§6-5 강제) |
| ✅ **필수** | `performance-caching-staletime` | Draft | M3 명시 필수 |
| ✅ **필수** | `fe-accessibility-skeleton-ui` | Approved | G2 명시 필수 |
| ✅ **필수** | `portfolio-management-e2e` | Approved | Go/No-Go 포트폴리오·공시 |
| ⚠️ **권장** | `pricing-nav-auth-consistency` | Approved | 전환 퍼널 UX 버그 |
| ⚠️ **권장** | `oauth-consent-data-integrity` | Draft | 정보통신망법 동의 시각 |
| ⚠️ **권장** | `dashboard-real-data` | Draft | BM Free 5건 제한·M3 연계 |
| ⚠️ **권장** | `user-profile-investment-experience` | Draft | 온보딩 완결성 |
| ⚠️ **권장** | `kakao-notification-channel` | Draft | 이메일 폴백 우선 (외부 의존 분리) |
| ⚠️ **권장** | `portfolio-review-followup` | Draft | 코드 품질·UX 완성도 |
| 🔵 **이후** | `payment-pg-integration` | Draft | M5 선택 / PG 계약 외부 의존 |
| 🔵 **이후** | `frontend-share-card-image` | Draft | G8 선택 / 7월 중 |
| 🔵 **이후** | `dashboard-eval-pnl` | Draft | KRX 현재가 인프라 미완성 |
| 🔵 **이후** | `portfolio-csv-upload` | Draft | P3 편의 기능 |
| 🔵 **이후** | `review-frontend-auth-capture` | Draft | 개발 도구 개선 |
| 🔵 **이후** | `review-frontend-hover-capture` | Draft | 개발 도구 개선 |4831


---

## ✅ MVP 필수 (6개)

### 1. `be-api-alignment-mvp-r1` — FE↔BE API 정합 R1

**판정 근거**: P0 버그 포함. Go/No-Go 체크리스트 "공시 조회·알림 수신" 항목에 직결.

| 항목 | 내용 |
|------|------|
| P0 이슈 | `GET /notifications` 응답 형식 불일치 — FE는 배열 기대, BE는 페이지네이션 객체 반환 → 알림 페이지 완전 broken |
| P1 이슈 | 공시 피드 `rcept_dt` 형식 불일치 → 날짜 그루핑("오늘" / "어제") 오동작 |
| 구현 비용 | DB 변경 없음. BE 응답 포맷 + FE 타입 수정만 필요 (低) |
| 의존성 | 없음 — 단독 처리 가능 |

---

### 2. `oauth-consent-enforcement` — OAuth 동의 강제화

**판정 근거**: E4 High-Security. 자본시장법 DISCLAIMER 동의 우회 가능 → §7 절대 금지 위반.

| 항목 | 내용 |
|------|------|
| 보안 이슈 | 소셜 가입 직후 URL 직접 변경으로 DISCLAIMER 미동의 상태에서 서비스 전체 접근 가능 |
| 법적 근거 | CLAUDE.md §7 "자본시장법 경계 — DISCLAIMER 동의 법적 필수" |
| 추가 이슈 | `?oauth=true` URL 조작으로 이메일 계정이 소셜 동의 API 호출 가능 (M-S1) |
| 구현 비용 | `middleware.ts` 동의 체크 + `consent_completed` 필드 추가. FE 중심 (低) |
| 의존성 | `oauth-consent-data-integrity` (V19 마이그레이션)와 순서 의존 — 이 Spec 먼저 |

---

### 3. `portfolio-search-keyboard-nav` — 종목 검색 키보드 네비게이션

**판정 근거**: WCAG 2.1 AA 필수 항목. CLAUDE.md §6-5 가 프로젝트 절대 규칙으로 명시.

| 항목 | 내용 |
|------|------|
| 접근성 이슈 | 종목 검색 드롭다운이 마우스 전용 — ArrowDown/ArrowUp/Enter 미지원 |
| 규정 근거 | WAI-ARIA 1.2 Combobox Pattern 필수 + CLAUDE.md §6-5 강제 |
| 영향 페르소나 | E(시니어 투자자 — 키보드 의존) → 종목 등록 자체가 불가 |
| 구현 비용 | FE 단일 컴포넌트 수정. `aria-activedescendant` + 키 핸들러 추가 (低) |

---

### 4. `performance-caching-staletime` — 성능·캐싱 보강

**판정 근거**: 마일스톤 M3(6/23~6/25) 명시 필수 작업. DoS 가드 부재.

| 항목 | 내용 |
|------|------|
| BE 이슈 | `resolveStockCodes()`가 매 공시 피드 요청마다 portfolios SELECT → 핫스팟 |
| FE 이슈 | TanStack Query `staleTime` 전체 미설정 → 포커스 복귀마다 전체 재요청 |
| 보안 이슈 | `DisclosureController.list()` size 상한 없음 → DoS 가드 부재 |
| 구현 비용 | `@Cacheable` 어노테이션 + Caffeine 설정 + FE staleTime 추가 (低~中) |
| 의존성 | `dashboard-real-data` staleTime 항목과 내용 겹침 → 병행 처리 권장 |

---

### 5. `fe-accessibility-skeleton-ui` (Approved) — 접근성·Skeleton·UI 완성도

**판정 근거**: 마일스톤 G2 명시 필수. Go/No-Go "모든 화면 면책 문구·접근성" 체크와 연결.

| 항목 | 내용 |
|------|------|
| 접근성 | WCAG 2.1 AA 위반 3건 (스킵 네비 없음, TierGate aria 없음, 커스텀 체크박스 스크린리더 미지원) |
| 로딩 UX | 모든 로딩 상태가 "불러오는 중..." 텍스트 → Skeleton으로 교체 |
| UI 미완성 | TopBar 검색창 input만 있고 동작 없음 |
| 기술 검토 | 이미 Approved — 구현 진입 가능 |

---

### 6. `portfolio-management-e2e` (Approved) — 포트폴리오 종목 관리 E2E

**판정 근거**: 포트폴리오 등록은 Go/No-Go "종목 등록 후 DART 공시 수집 → 분석 결과 표시" 체크의 진입점.

| 항목 | 내용 |
|------|------|
| BE 이슈 | `PortfolioResponse`에 `corp_name` 미포함 → 종목명 없이 종목코드만 표시 |
| FE 이슈 | `usePortfolios()` `staleTime` 미설정 → 매 마운트 재요청 |
| 기능 이슈 | per-stock `notify_enabled` BE 미지원 → 알림 on/off 설정 무효 |
| 기술 검토 | 이미 Approved — 구현 진입 가능 |

---

## ⚠️ MVP 권장 (6개)

### 7. `pricing-nav-auth-consistency` (Approved)

**이유**: 로그인 사용자에게 "로그인 / 무료로 시작" CTA 노출 — 명백한 UX 버그.
Free→Pro 업셀 퍼널의 신뢰도에 직접 영향. FE only 수정, BE 변경 없음. 구현 비용 매우 낮음.
기술 검토 이미 완료(Approved)이므로 `oauth-consent-enforcement` 이후 즉시 처리 가능.

---

### 8. `oauth-consent-data-integrity`

**이유**: `autoSignup()` 시 `terms_agreed_at = now()`가 실제 동의 전 시각으로 저장.
정보통신망법 상 동의한 시각의 증명 의무 → 데이터 정합성 문제. 좀비 계정 누적도 부수 리스크.
Flyway V19 마이그레이션 필요. `oauth-consent-enforcement`(E4) 이후 순서 처리.
법적 리스크 내포로 런치 전 완료 권장.

---

### 9. `dashboard-real-data`

**이유**: Free 5건/일 제한 미구현(BM 핵심 — 미구현 시 Free 사용자가 무제한 사용 가능).
staleTime 미설정은 `performance-caching-staletime`과 60% 겹침 → M3 일정(6/23~6/25) 병행 처리.
Skeleton 항목은 `fe-accessibility-skeleton-ui`(Approved)와 중복 — 해당 Spec으로 커버됨.

---

### 10. `user-profile-investment-experience`

**이유**: 회원가입 STEP 4/4에서 수집한 투자 경험·주 사용 시점이 DB 미저장(주석 처리 상태).
Go/No-Go 체크리스트에 명시 없으나 완성된 온보딩 기대하는 신규 사용자 경험에 구멍.
Flyway V10 + UserEntity + FE 타입 수정으로 해결. 구현 비용 낮음(低).
런치 후 추가 가능하지만 온보딩 완결성 측면에서 MVP 이전 처리 권장.

---

### 11. `kakao-notification-channel`

**이유**: 알림은 서비스 핵심 가치 전달 채널. 현재 `KakaoAlimtalkClient`가 placeholder 모드에서 실 API 호출 시도 → 실패.
Go/No-Go "알림 발송(이메일 폴백 최소 1채널)" 조건을 충족하려면 이메일 채널 SMTP 설정 최소 필요.
카카오 비즈메시지 채널 미승인은 외부 프로세스 → 내부 구현(SMTP 설정·폴백 활성화)과 분리 처리 가능.

**처리 전략**: 이메일 폴백 활성화(내부) → Go/No-Go 통과 → 카카오 알림톡 채널 승인 후 전환.

---

### 12. `portfolio-review-followup`

**이유**: `portfolio-management-e2e` 구현 후 코드 리뷰에서 미수정된 Medium 3건 + Low 4건.
M-1(`toResponse()` 오버로드 혼재 — 유지보수 위험), M-6(Bell 알림 링크 누락 — UX).
P0/P1 아님이므로 "필수" 분류에서 제외. 단, `portfolio-management-e2e`(필수)와 함께 처리하면 효율적.

---

## 🔵 MVP 이후 (6개)

### 13. `payment-pg-integration`

**이유**: 마일스톤 M5 "선택" 명시. PG사(카카오페이·Toss) 계약 체결 + 테스트 키 발급 3~5 영업일 외부 의존.
일정 내 불가 시 Free 티어만 소프트 런치 전략 이미 확정. BE/FE 모두 신규 구현 필요한 高 난이도.
**연기 조건**: 7/3 런치 후 PG 계약 완료 시 즉시 착수.

---

### 14. `frontend-share-card-image`

**이유**: 마일스톤 G8 "선택 / 7월 중" 명시. 현재 `/share` 페이지 텍스트 공유 동작.
바이럴 성장 지원으로 중요하나 런치 blocking 아님. html2canvas 또는 satori OG 중 방식 결정도 필요.
**연기 조건**: 7월 중 처리.

---

### 15. `dashboard-eval-pnl`

**이유**: `stocks` 테이블에 `price` 컬럼 자체 없음. `KrxClient`가 현재가 미수집.
이 Spec 구현 전에 Flyway 마이그레이션 + KrxClient 전면 수정 + BE API 신규 필요 → 의존 인프라 미완성.
마일스톤 로드맵 "Stage 4 주가 반응 분석 8월"과 연계.
**연기 조건**: KrxClient 현재가 수집 인프라 완성 후 처리.

---

### 16. `portfolio-csv-upload`

**이유**: 스펙 내 우선순위 P3 명시. 현재 수동 종목 검색·등록 동작 → 온보딩 blocking 아님.
증권사별 CSV 포맷(삼성증권·키움·NH 등) 상이해 파싱 로직 설계 추가 필요.
**연기 조건**: 런치 후 편의 기능으로 추가.

---

### 17. `review-frontend-auth-capture`

**이유**: `dc-review-frontend` 스킬 개선 — Playwright에 인증 쿠키 주입으로 로그인 화면 캡처.
프로덕션 기능 비관여. 개발 도구 고도화.
**연기 조건**: 런치 후 리뷰 파이프라인 고도화 시 처리.

---

### 18. `review-frontend-hover-capture`

**이유**: Playwright 호버 상태 스크린샷 추가. 프로덕션 기능 비관여. 개발 도구.
**연기 조건**: 런치 후 처리.

---

## 구현 순서 제안 (필수 Spec 기준)

> ⚠️ **staleTime 충돌 주의**: `portfolio-management-e2e` R2(usePortfolios 60초)와
> `performance-caching-staletime` R8(usePortfolios 2분)이 같은 훅에 다른 값을 지정.
> portfolio-management-e2e 구현 시 **R2(staleTime)는 건너뛰고**,
> performance-caching-staletime에서 전체 staleTime을 일괄 처리한다.

```
W2 (6/23~6/27) — M3 + 필수 처리
  1. be-api-alignment-mvp-r1          (P0 버그 선행 수정)
  2. portfolio-management-e2e         (Approved → 구현, R2 staleTime 제외)
  3. performance-caching-staletime    (M3 필수 + usePortfolios staleTime 통합 처리)
     └ dashboard-real-data            (staleTime 겹침 — 병행)
  4. oauth-consent-enforcement        (법적 필수)
     └ oauth-consent-data-integrity   (enforcement 접근법 결정 후 직후 처리 — V19 마이그레이션)
  5. portfolio-search-keyboard-nav    (WCAG 단독 처리 가능)

W3 (6/28~7/2) — 필수 마무리 + 권장 처리 + QA
  6. fe-accessibility-skeleton-ui     (Approved → 구현, 범위 큼 — W3로 이동)
  7. pricing-nav-auth-consistency     (Approved → 빠른 처리)
  8. user-profile-investment-experience
  9. kakao-notification-channel       (이메일 폴백 활성화)
 10. portfolio-review-followup
 11. M6 최종 QA + 보안 점검
```

> `fe-accessibility-skeleton-ui`는 5개+ 파일 수정 + axe-core 검증 포함으로 W2 마지막에 배치하면
> 일정 압박이 크다. W3 초반에 처리해도 Go/No-Go 기준일(7/3)까지 여유 있음.

---

## 참고 문서

- [[milestone-2026-mvp-launch]] — Go/No-Go 체크리스트 7개 항목
- [[docs/specs/README]] — Spec 현황 MOC
- [[CLAUDE]] §6-5 접근성, §7 절대 금지
