---
type: spec
status: Approved
created: 2026-06-17
updated: 2026-06-17
---

# 온보딩·포트폴리오 코드 리뷰 수정 Spec

> 상태: **Approved** (2026-06-17, dc-tech-review 승인)

## 배경 / 목적

알림 센터(notifications) 온보딩 배너 추가 + 모바일 PortfolioSheet 높이 조정 작업 후 실시한
`/dc-review-code` 5-에이전트 리뷰에서 Critical 1건·High 9건·Medium 7건·Low 5건이 발견됨.

- **Critical**: 배포 즉시 Flyway 부트 실패 위험
- **High**: 사용자 세션 전체 삭제(DoS), 접근성 위반, 불필요한 서버 요청 루프
- **Medium**: 유지보수성·입력 검증·번들 크기
- **Low**: 컨벤션·명시성 개선

영향 페르소나: 온보딩 중인 신규 투자자(페르소나 A, C, E).
BM 티어: Free (모든 온보딩 흐름).

---

## 요구사항

### Critical

- [ ] **C-1** Flyway V10 충돌 수정 — `V10__add_onboarding_completed_at.sql` → `V20__add_onboarding_completed_at.sql` 로 rename (기존 `V10__seed_stocks.sql` 충돌 해소; V19는 [[oauth-consent-data-integrity]] spec에 예약됨)

### High

- [ ] **H-1** fire-and-forget 온보딩 루프 수정 — `signup/complete/page.tsx` useEffect 내 `completeOnboarding()` 호출에 에러 핸들링 추가 (뮤테이션 실패 시 silently swallow 방지)
- [ ] **H-2** PortfolioSheet 스텝 검증 명시화 — `onSubmit` 진입 전 `selectedStock` null guard 명시적 early-return 또는 Step 1에서 Step 2로 넘어갈 때 유효성 검증 추가
- [ ] **H-3** OAuth refresh 전체 삭제 DoS 방지 — `AuthService.java:240` `deleteByUserId(userId)` → `deleteByTokenHash(hash)` (현재 세션 토큰만 삭제)로 변경하여 멀티 디바이스 세션 무효화 방지
- [ ] **H-4** V10 백필 조건 강화 — `consent_logs` 존재 여부만 검사하는 WHERE → consent_type 또는 약관 동의 완료 단계 확인 조건으로 교체 (혹은 backfill 대상 명시적 한정)
- [ ] **H-5** reason 정보 유출 차단 — `GlobalExceptionHandler.java:71-72` `ex.getReason()` 직접 노출 → 화이트리스트 메시지로 교체 또는 reason 로깅 후 클라이언트에는 generic 메시지 반환
- [ ] **H-6** PortfolioSheet 정적 ID → `useId()` 교체 — `sheet-avg-price`, `sheet-quantity`, `sheet-price-hint`, `sheet-quantity-hint` 4개 정적 id를 `React.useId()`로 생성한 prefix 기반 unique id로 교체 (WCAG 2.1 §4.1.1 중복 id 위반)
- [ ] **H-7** `useSheetSide` 커스텀 훅 추출 — `signup/complete/page.tsx:238-244`, `notifications/page.tsx:59-65` 중복된 matchMedia useEffect → `src/hooks/useSheetSide.ts` 공통 훅으로 분리
- [ ] **H-8** `useNotificationSettings` staleTime 추가 — `frontend/src/lib/api/notifications.ts:58-63` 쿼리에 `staleTime: 60_000` (또는 적절한 값) 추가 (현재 포커스 복귀마다 refetch)
- [ ] **H-9** notifications 페이지 portfolios 불필요 eager-fetch 방지 — 배너 표시 조건이 `portfolios.length === 0`일 때만 의미 있으므로 `enabled` 옵션 또는 조건부 쿼리 적용 검토

### Medium

- [ ] **M-1** `isError` 에러 타입 구분 — `NotifDialog`의 `isSettingsError` 단일 boolean → 에러 종류(401/403/404/500)별 메시지 분기 또는 `error` 객체 참조로 교체
- [ ] **M-2** `avg_buy_price` 최대값 검증 추가 — `PortfolioSheet.tsx:150-153` register 옵션에 `max: { value: 999_999_999, message: "범위를 초과했습니다" }` 추가 (BE @Max와 정합)
- [ ] **M-3** `termsAgreedAt` 사전 동의 오염 방지 — `AuthService.java:116-117, 283-284` OAuth 신규 가입 시 `.termsAgreedAt(now)` 자동 설정 → null로 두고 실제 약관 동의 시점에 업데이트하도록 변경
- [ ] **M-4** GlobalExceptionHandler default 코드 개선 — `case default -> "ERROR"` → 5xx는 `"INTERNAL_SERVER_ERROR"`, 429는 `"TOO_MANY_REQUESTS"` 등 분리
- [ ] **M-5** useEffect 의존성 suppression 제거 — `signup/complete/page.tsx:234` eslint-disable 주석 제거 후 `completeOnboarding`을 `useCallback`으로 안정화하거나 ref 패턴 적용
- [ ] **M-6** 매직 넘버 상수화 — `66dvh`, `640px` → `BOTTOM_SHEET_MIN_HEIGHT = "min-h-[66dvh]"`, `SM_BREAKPOINT = "(min-width: 640px)"` 등 named constant로 추출
- [ ] **M-7** react-hook-form 번들 영향 검토 — `PortfolioSheet` 공유 컴포넌트에 react-hook-form 포함 여부가 모든 import 경로에 영향; `next/dynamic` lazy 또는 분리 검토

### Low

- [ ] **L-1** BE `@Max` 검증 추가 — `PortfolioRequest.java` `avgBuyPrice`, `quantity` 필드에 `@Max` + `@DecimalMax` 어노테이션 추가
- [ ] **L-2** Caffeine 단일 인스턴스 주석 — 멀티 인스턴스 배포 시 Caffeine 캐시가 JVM 단위임을 설명하는 주석 추가 (실서비스 전 Redis 대체 필요)
- [ ] **L-3** `completeOnboarding` 메서드 @Transactional 명시 — `UserService.java:54` 클래스 레벨 `@Transactional` 의존 대신 메서드 레벨 명시 (`@Transactional`) 추가
- [ ] **L-4** `fetchMe()` 중복 호출 제거 — `CompletePage` useEffect에서 `fetchMe()` 중복 제거 또는 필요 여부 재검토
- [ ] **L-5** RPC 스타일 엔드포인트 재검토 — `POST /users/me/onboarding-complete` → `PATCH /users/me` (`{"onboarding_completed_at": "..."}`) 또는 `POST /users/me/onboarding/completion` REST 컨벤션으로 변경 검토

---

## 영향 범위 (조사 결과)

### 영향 레이어

| 레이어 | 파일 | 이슈 |
|--------|------|------|
| **DB 마이그레이션** | `backend/src/main/resources/db/migration/V20__add_onboarding_completed_at.sql` (rename 후) | C-1, H-4 |
| **BE 서비스** | `backend/src/main/java/com/dartcommons/user/services/AuthService.java` | H-3, M-3 |
| **BE 서비스** | `backend/src/main/java/com/dartcommons/user/services/UserService.java` | L-3 |
| **BE 컨트롤러** | `backend/src/main/java/com/dartcommons/user/controllers/UserController.java` | L-5 |
| **BE 공통** | `backend/src/main/java/com/dartcommons/shared/exception/GlobalExceptionHandler.java` | H-5, M-4 |
| **FE 공유 컴포넌트** | `frontend/src/components/domain/PortfolioSheet.tsx` | H-2, H-6, M-2, M-7 |
| **FE 페이지** | `frontend/src/app/(auth)/signup/complete/page.tsx` | H-1, H-7, M-5 |
| **FE 페이지** | `frontend/src/app/(app)/notifications/page.tsx` | H-7, H-9 |
| **FE API** | `frontend/src/lib/api/notifications.ts` | H-8, M-1 |
| **FE 훅(신규)** | `frontend/src/hooks/useSheetSide.ts` | H-7 |

### DB 변경

- C-1: 파일 rename만 (스키마 변경 없음, 단 `git mv` 필요)
- H-4: V10 백필 UPDATE 쿼리 수정 → **적용된 마이그레이션은 수정 불가** (Flyway 불변 원칙) → rename 후 백필 수정도 새 파일(V20) 내에서 처리

### 외부 계약

- H-3, L-5: UserController REST API 변경 시 FE `useCompleteOnboarding` 훅의 엔드포인트 URL 동기화 필요
- H-5: 에러 응답 스키마 변경 시 FE `ApiException` 파싱 로직 정합성 확인

---

## 관련 패턴 / 과거 사례

- `useId()` 패턴: shadcn/ui Dialog 컴포넌트가 이미 `useId()` 기반 id 생성을 사용 — 동일 패턴 적용
- staleTime: `usePortfolios()` (`staleTime: 60_000`), `useUnreadCount()` (`staleTime: 30_000`) 참고
- MediaQuery 커스텀 훅: `signup/complete/page.tsx`에 이미 동일 로직 존재 — 추출 검증됨
- GlobalExceptionHandler 에러 코드: FE `API_ERROR_CODES` (`frontend/src/lib/api/errorCodes.ts`) 상수와 정합 유지 필요
- Flyway 불변 원칙: `docs/CLAUDE.md §6-3` — 적용된 파일 수정 금지, 신규 버전 파일만 추가

---

## 리스크 / 법적 검토

| 항목 | 리스크 | 대응 |
|------|--------|------|
| C-1 미수정 시 | Spring Boot 부트스트랩 실패 (Flyway checksum 충돌) | 최우선 처리 |
| H-3 미수정 시 | 동일 사용자 멀티 디바이스 세션 전체 강제 로그아웃 | OAuth 재진입 시 DoS 유사 패턴 |
| H-5 미수정 시 | 내부 DB 테이블명·스택 정보 외부 노출 가능 | 정보보호법·보안 정책 위반 가능성 |
| M-3 미수정 시 | 약관 미확인 사용자에게 동의 기록 생성 | 개인정보보호법 §22 (동의 유효성) 리스크 |
| H-6 미수정 시 | WCAG 2.1 §4.1.1 — 동일 페이지에 PortfolioSheet가 2회 마운트되면 중복 id로 a11y 파괴 | 접근성 AA 요건 위반 |

---

## 권장 구현 방향

### 우선순위 순서

1. **C-1 먼저** — 배포 블로커. `git mv` 후 V19로 rename.
2. **H-6 (useId)** — PortfolioSheet 공유 컴포넌트 변경으로 signup/complete·notifications 동시 해결.
3. **H-7 (useSheetSide)** — 훅 추출 후 두 페이지 동시 교체.
4. **H-3 (deleteByUserId)** — 토큰 삭제 범위 좁히기. 현재 세션 해시만 삭제.
5. **H-8 (staleTime)** — 1줄 추가.
6. **H-1 (fire-and-forget)** — mutation 에러 핸들링 (toast 또는 retry).
7. 나머지 H/M/L — 배치 처리 가능.

### H-6 구현 예시 (useId)

```tsx
// PortfolioSheet.tsx
import { useId } from "react";

export function PortfolioSheet(...) {
  const uid = useId();
  const ids = {
    avgPrice:     `${uid}-avg-price`,
    quantity:     `${uid}-quantity`,
    priceHint:    `${uid}-price-hint`,
    quantityHint: `${uid}-quantity-hint`,
  };
  // ...
  <input id={ids.avgPrice} aria-describedby={ids.priceHint} ... />
  <p id={ids.priceHint} ...>손익 계산에 사용됩니다.</p>
}
```

### H-7 구현 예시 (useSheetSide)

```ts
// frontend/src/hooks/useSheetSide.ts
import { useEffect, useState } from "react";

const SM_BREAKPOINT = "(min-width: 640px)";

export function useSheetSide(): "bottom" | "right" {
  const [side, setSide] = useState<"bottom" | "right">("bottom");
  useEffect(() => {
    const mq = window.matchMedia(SM_BREAKPOINT);
    const update = () => setSide(mq.matches ? "right" : "bottom");
    update();
    mq.addEventListener("change", update);
    return () => mq.removeEventListener("change", update);
  }, []);
  return side;
}
```

### H-3 구현 방향

`deleteByUserId` 대신 현재 요청에 사용된 refresh token hash만 삭제:
```java
// 온보딩 미완료 재진입 — 현재 세션 토큰만 무효화 (멀티 디바이스 보호)
// refreshTokenRepository.deleteByUserId(user.getId()); // ← DoS 위험 제거
// 현재 세션에서 넘어온 refresh token이 있으면 해당 hash만 삭제
// (OAuth 흐름에서 refresh token 없이 진입하므로 삭제 자체가 불필요한 케이스)
```

실제로 OAuth 초기 진입 시점에는 refresh token이 없으므로 `deleteByUserId` 자체를 제거해도 안전.

### C-1 구현 방향

```bash
git mv backend/src/main/resources/db/migration/V10__add_onboarding_completed_at.sql \
        backend/src/main/resources/db/migration/V20__add_onboarding_completed_at.sql
```

> V19는 [[oauth-consent-data-integrity]] spec(`V19__nullable_agreed_at.sql`)에 예약됨 → V20 사용.

H-4(백필 조건)도 동일 파일에서 수정:
```sql
-- 개선: consent_logs 중 TERMS + PRIVACY 모두 동의한 사용자만 백필
UPDATE users u
SET onboarding_completed_at = u.created_at
WHERE EXISTS (
    SELECT 1 FROM consent_logs cl
    WHERE cl.user_id = u.id
      AND cl.consent_type = 'TERMS'
) AND EXISTS (
    SELECT 1 FROM consent_logs cl
    WHERE cl.user_id = u.id
      AND cl.consent_type = 'PRIVACY'
);
```

---

## Tech Review (dc-tech-review · 2026-06-17)

### 아키텍처 분해

- 영향 레이어: `backend/user` (AuthService·UserService·UserController·GlobalExceptionHandler·PortfolioRequest), `frontend` (PortfolioSheet 공유 컴포넌트·signup/complete·notifications 페이지·notifications.ts)
- 신규 파일: `frontend/src/hooks/useSheetSide.ts`, `backend/.../db/migration/V20__add_onboarding_completed_at.sql`
- 수정 파일: `V10__add_onboarding_completed_at.sql` (rename), `AuthService.java`, `GlobalExceptionHandler.java`, `PortfolioRequest.java`, `UserService.java`, `PortfolioSheet.tsx`, `signup/complete/page.tsx`, `notifications/page.tsx`, `notifications.ts`

### 검토 보정 (이 스펙 결정 사항)

| 이슈 | 결정 | 근거 |
|------|------|------|
| H-2 단계 검증 | **제외 (false positive)** | PortfolioSheet Step 2 form은 `{selectedStock && <form>}` 조건부 렌더 — Step 1 미완료 시 폼 자체 접근 불가. `onSubmit` guard는 방어적 중복으로 이미 충분 |
| H-9 portfolios eager-fetch | **추가 작업 없음** | `usePortfolios()`에 `staleTime: 60_000` 이미 설정 → 60초 캐시 유효. 배너 판단에 portfolios 데이터 필수이므로 `enabled` 조건화 불가 |
| M-3 termsAgreedAt | **이관** | [[oauth-consent-data-integrity]] spec의 V19 Wave 1과 완전 중복 → 해당 spec 완료 후 처리 |
| M-7 react-hook-form | **Low 재분류** | react-hook-form ~13kb gzipped. signup/complete·notifications 둘 다 이미 사용 중이라 code-split 효과 제한적. MVP 이후 검토 |
| L-5 엔드포인트 명 | **MVP 이후** | RPC → REST 전환 시 UpdateMeRequest 확장 + UserService.updateMe 수정 연쇄 필요. 현행 유지 권장 |

### 작업 카드

#### Wave 1 — DB 마이그레이션 (부트 블로커, 선행 필수)

| # | 작업 | 파일 | 레이어 | 난이도 | 의존성 |
|---|------|------|--------|--------|--------|
| 1 | C-1: V10 → V20 rename (`git mv`) | `V10__add_onboarding_completed_at.sql` | DB | 하 | — |
| 2 | H-4: 백필 조건 강화 (TERMS+PRIVACY 모두 존재 시만) | `V20__add_onboarding_completed_at.sql` | DB | 하 | #1 (동일 파일) |

> Wave 1은 Spring Boot 부트스트랩 블로커. 반드시 단독 선행. `ddl-auto: validate`이므로 rename만으로 스키마 변경 없음.

#### Wave 2 — BE 보안·신뢰성

| # | 작업 | 파일 | 레이어 | 난이도 | 의존성 |
|---|------|------|--------|--------|--------|
| 3 | H-3: `deleteByUserId` 제거 — 온보딩 재진입 경로 | `AuthService.java:240` | BE | 하 | W1 |
| 4 | H-5: reason 화이트리스트 처리 — `getReason()` → 로그 후 status 기반 generic message | `GlobalExceptionHandler.java:71-72` | BE | 하 | — |
| 5 | M-4: default `"ERROR"` → 5xx/429 코드 분리 | `GlobalExceptionHandler.java:68` | BE | 하 | #4 (동일 파일) |
| 6 | L-1: `@DecimalMax` + `@Max` 추가 | `PortfolioRequest.java` | BE | 하 | — |
| 7 | L-3: `completeOnboarding()` 메서드 레벨 `@Transactional` 명시 | `UserService.java:54` | BE | 하 | — |

#### Wave 3 — FE 공유 컴포넌트

| # | 작업 | 파일 | 레이어 | 난이도 | 의존성 |
|---|------|------|--------|--------|--------|
| 8 | H-6: 정적 id 4개 → `useId()` prefix 방식으로 교체 | `PortfolioSheet.tsx` | FE | 하 | — |
| 9 | M-2: `avg_buy_price` `max` 검증 추가 (`999_999_999`) | `PortfolioSheet.tsx` | FE | 하 | #8 (동일 파일) |
| 10 | H-7: `useSheetSide` 훅 신규 추출 (`SM_BREAKPOINT` 상수 포함) | `src/hooks/useSheetSide.ts` (신규) | FE | 하 | — |
| 11 | M-6: `BOTTOM_SHEET_MIN_HEIGHT` 상수화 + 두 페이지 교체 | `notifications/page.tsx`, `useSheetSide.ts` | FE | 하 | #10 |
| 12 | H-7 적용: signup/complete·notifications useSheetSide 호출로 교체 | `signup/complete/page.tsx`, `notifications/page.tsx` | FE | 하 | #10 |

#### Wave 4 — FE 페이지 로직·API

| # | 작업 | 파일 | 레이어 | 난이도 | 의존성 |
|---|------|------|--------|--------|--------|
| 13 | H-1: `completeOnboarding` fire-and-forget → `onError` toast 추가 | `signup/complete/page.tsx:231-232` | FE | 하 | — |
| 14 | M-1: `isSettingsError` boolean → `error` 객체 에러 타입 분기 | `signup/complete/page.tsx` (NotifDialog) | FE | 하 | — |
| 15 | M-5: eslint-disable 제거 → `completeOnboarding` deps 명시 or useRef 패턴 | `signup/complete/page.tsx:234` | FE | 하 | #13 (연관) |
| 16 | L-4: `fetchMe()` 중복 호출 제거 검토 | `signup/complete/page.tsx:229` | FE | 하 | — |
| 17 | H-8: `useNotificationSettings` staleTime 추가 (`60_000`) | `notifications.ts:58-63` | FE | 하 | — |

#### Wave 5 — 선택적 개선 (우선순위 낮음)

| # | 작업 | 파일 | 레이어 | 난이도 | 의존성 |
|---|------|------|--------|--------|--------|
| 18 | L-2: Caffeine 단일 인스턴스 경고 주석 | `CacheConfig.java` (위치 확인 필요) | BE | 하 | — |
| 19 | M-7(Low): PortfolioSheet next/dynamic lazy 가능성 검토 | `PortfolioSheet.tsx` | FE | 중 | — |

### DB / 마이그레이션 영향

- **V20__add_onboarding_completed_at.sql** (C-1 + H-4 통합):
  - `ALTER TABLE users ADD COLUMN onboarding_completed_at TIMESTAMPTZ;` — 스키마 변경
  - 백필 조건: `consent_type IN ('TERMS','PRIVACY')` 양쪽 모두 존재 시만 업데이트
  - `V10__seed_stocks.sql`과의 충돌이 원인이므로 **V10 파일을 절대 수정하지 말 것** — `git mv`로 rename만
  - V19는 [[oauth-consent-data-integrity]] spec 예약 → V20 사용 확정

### 외부 계약 영향

- H-5 / M-4: GlobalExceptionHandler 에러 응답의 `code`·`message` 필드 변경 → FE `API_ERROR_CODES` (`frontend/src/lib/api/errorCodes.ts`) 상수 정합성 확인 필요
- H-3: `deleteByUserId` 제거 시 만료 토큰 자동 정리는 `deleteExpiredTokens` 배치에 위임 — 배치 주기 확인 권장 (`@Scheduled` 설정 확인)
- L-4: `fetchMe()` 제거 검토 시 — `useAuthStore`의 `user` 상태가 signup/complete 진입 전에 이미 채워져 있는지 미들웨어 흐름 재확인 필요

### 리스크 & 법적 검토

| 리스크 | 영향 | 대응 |
|--------|------|------|
| C-1 미처리 | Spring Boot 부트 실패 즉시 발생 | **Wave 1 최우선** |
| H-4 백필 과잉 | 온보딩 미완료 기존 사용자가 returning user로 잘못 분류 → 온보딩 재진입 차단 | Wave 1에서 TERMS+PRIVACY 조건으로 수정 |
| H-5 미처리 | ResponseStatusException reason에 DB 테이블명·내부 경로 포함 가능 → 정보보호법 리스크 | Wave 2 처리 |
| H-3 deleteByUserId 제거 후 | 만료 토큰 누적 → `deleteExpiredTokens` 배치 주기 의존 | `@Scheduled` 주기 확인 (현재 설정 확인 필요) |
| M-3 이관 지연 | termsAgreedAt 자동 설정 → 개인정보보호법 §22 동의 유효성 리스크 지속 | oauth-consent-data-integrity spec V19 완료 시 즉시 처리 |

### 예상 wave 수

- **4개 구현 wave** (W1·W2·W3·W4) + W5 선택적 후처리
- W2·W3·W4는 BE/FE 레이어 분리로 병행 가능 (W2 BE 독립, W3 FE 컴포넌트 독립)
- Wave 1 → Wave 2 순서는 필수 (부트 블로커 해소 후 BE 수정)
