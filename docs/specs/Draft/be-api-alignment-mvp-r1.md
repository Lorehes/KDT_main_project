---
type: spec
status: Draft
created: 2026-06-22
updated: 2026-06-22
---

# BE/DB API 정합 MVP R1 Spec

> 상태: **Draft** (dc-plan 생성)
> 근거: dc-review-frontend 2026-06-22 실행 결과 + FE API 계약 전수 비교
> 참고 Spec: [[payment-pg-integration]] (결제 연동은 별도 Spec, 본 범위 제외)

---

## 배경 / 목적

FE가 사실상 최종 상태(UI Grade **A**, 28개 REST 엔드포인트 정의 완료)에 이르렀다.
BE 컨트롤러는 대부분 구현됐으나, **FE ↔ BE 계약 불일치 2건**(P0·P1)이 실 동작을 차단한다.
또한 Stage 3/5 미구현 필드에 대한 문서화 공백이 있어 후속 개발자 혼선 방지를 위해 정리한다.

- **대상 페르소나**: 모든 사용자 (A~F) — 알림 이력 UI·공시 피드 날짜 그루핑
- **BM 티어**: 전 티어 공통 (알림·공시 기본 기능)

---

## 요구사항

### R1 — [P0] GET /notifications 응답 형식 수정 (BE)

> **문제**: `NotificationController.list()`가 `List<NotificationResponse>`를 반환.
> FE `notifications.ts`와 `api_spec.md §1.4`는 `PageResponse<T>` 형식 요구.
>
> FE 기대 응답:
> ```jsonc
> {
>   "content": [{ "id": 1, "disclosure_id": 2, ... }],
>   "page": { "number": 0, "size": 20, "total_elements": 137, "total_pages": 7 }
> }
> ```
> 현재 BE 응답: `[{ "id": 1, ... }]` (배열 직접 반환)

- [ ] `NotificationController.list()` 시그니처 변경 — `List<>` → `PageResponse<NotificationResponse>` 반환
- [ ] `@RequestParam` 추가 — `page` (기본 0), `size` (기본 20, max 100), `sort` (기본 `created_at,desc`)
- [ ] `NotificationHistoryService.list(Long userId)` → `list(Long userId, Pageable pageable)` 변경
- [ ] `NotificationRepository`에 Pageable 오버로드 추가 — `Page<NotificationEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable)`
- [ ] `PageResponse.from(Page<T>)` 정적 팩토리 활용 (이미 `shared/dto/PageResponse.java` 존재)

### R2 — [P1] rcept_dt 형식 수정 (BE)

> **문제**: `DisclosureListItemResponse.rceptDt` = `d.getRceptDt().toString()` → "2024-06-22" (YYYY-MM-DD).
> FE `disclosures/page.tsx` `groupByDate()` 함수가 YYYYMMDD ("20240622") 형식과 비교.
>
> ```typescript
> // FE (page.tsx)
> const today = new Date().toISOString().slice(0, 10).replace(/-/g, ""); // "20240622"
> const label = d.rcept_dt === today ? "오늘" : ...;  // 항상 false — 그루핑 불가
> ```

- [ ] `DisclosureListItemResponse.from()` 수정 — `d.getRceptDt().toString()` → `d.getRceptDt().format(DateTimeFormatter.BASIC_ISO_DATE)` ("20240622" 포맷)
- [ ] Jackson 전역 `LocalDate` 직렬화 설정(`application.yml` 또는 `JacksonConfig`)이 있다면 공시 도메인만 예외 처리하거나 포맷터 일치 확인 필요
- [ ] `GET /disclosures/{id}` 단건 응답의 `rcept_dt`도 동일 형식으로 통일

### R3 — [P2] Stage 3/5 미구현 필드 문서화 (BE)

> **현황**: `AnalysisResponse.similar_disclosures` / `financial_context`는 의도적 null (주석 확인됨).
> FE는 이미 이 필드가 null일 때 Pro+ 업셀 CTA로 처리 중.

- [ ] `AnalysisResponse.java` 주석에 Stage 완료 시 교체 지점 명확히 기술 (이미 TODO 있음 — 확인·표준화)
- [ ] `api_spec.md §2.4` 에 "Stage 3 완료 전: null 반환, FE 업셀 CTA 표시" 명시 추가
- [ ] `similar_disclosures` 필드: Stage 3 RAG 구현 시 `AnalysisResult.stageDetails` JSONB에서 파싱 또는 별도 테이블 설계 → **후속 Spec에서 결정**

### R4 — [P3] FE 접근성 수정 (FE)

> **문제**: Playwright 자동검사 — `/signup` 페이지 버튼 1개 `aria-label` 누락.
> 분석: submit 버튼("가입하고 시작하기")에 텍스트 콘텐츠는 있으나 `aria-label` 속성 없음.
> 스크린리더는 버튼 텍스트를 읽으므로 실용적 영향 최소 — WCAG 2.1 AA 관점 보강.

- [ ] `/signup` 페이지 submit 버튼에 `aria-label="가입하고 시작하기"` 추가

---

## 영향 범위 (조사 결과)

### 영향 파일

| 파일 | 변경 내용 |
|------|----------|
| `backend/src/main/java/com/dartcommons/user/controllers/NotificationController.java` | 응답 타입 List → PageResponse, page/size/sort 파라미터 추가 |
| `backend/src/main/java/com/dartcommons/user/services/NotificationHistoryService.java` | list() Pageable 수용, Page<> 반환 |
| `backend/src/main/java/com/dartcommons/notification/repositories/NotificationRepository.java` | Pageable 오버로드 메서드 추가 |
| `backend/src/main/java/com/dartcommons/disclosure/dto/DisclosureListItemResponse.java` | rceptDt BASIC_ISO_DATE 포맷 |
| `backend/src/main/java/com/dartcommons/analysis/dto/AnalysisResponse.java` | 주석 보강 (코드 변경 없음) |
| `docs/개발명세서/api_spec.md` | §2.4 Stage 3 null 정책 명시 |
| `frontend/src/app/(auth)/signup/page.tsx` | submit 버튼 aria-label 추가 |

### DB 변경

**없음** — 현행 Flyway V1~V20 스키마가 모든 MVP 기능을 충족한다.
- `notifications` 테이블: `idx_notifications_user (user_id, created_at DESC)` 이미 존재 → Pageable 쿼리 최적화됨
- OTP 스토리지: Caffeine 인메모리 유지 (단일 인스턴스 MVP 범위)
- 결제 테이블: payment-pg-integration.md Draft 별도 처리

### 외부 계약 변경

없음. DART OpenAPI·카카오 알림톡·OAuth 프로바이더 계약 변경 없음.

---

## 현재 FE ↔ BE 정합성 전수 조사 결과

> dc-review-frontend 2026-06-22 + FE api/ 디렉토리 전수 분석 기반
> ✅ = 정상 / ⚠️ = 본 Spec 수정 대상 / 📌 = 의도적 미구현(후속 Spec) / 🚫 = 별도 Spec

| 도메인 | 엔드포인트 | 상태 | 비고 |
|--------|-----------|------|------|
| Auth | POST /auth/signup | ✅ | consent_logs INSERT + Caffeine 이메일 검증 완료 |
| Auth | POST /auth/login | ✅ | |
| Auth | POST /auth/logout | ✅ | refresh_tokens DELETE |
| Auth | POST /auth/refresh | ✅ | FE Route Handler → BE 경유 |
| Auth | GET /auth/oauth/{provider}/url | ✅ | OAuth state 관리 완료 |
| Auth | POST /auth/oauth/{provider}/callback | ✅ | |
| Auth | POST /auth/email/send-otp | ✅ | Caffeine 인메모리, rate limit |
| Auth | POST /auth/email/verify | ✅ | |
| User | GET /users/me | ✅ | 모든 FE 필드 포함 (phone_verified 포함) |
| User | PATCH /users/me | ✅ | |
| User | DELETE /users/me | ✅ | soft delete |
| User | POST /users/me/phone/verify | ✅ | Caffeine 5분 TTL |
| User | POST /users/me/phone/verify/confirm | ✅ | |
| User | POST /users/me/oauth-consent | ✅ | 멱등 처리 |
| User | POST /users/me/onboarding-complete | ✅ | |
| Portfolio | GET /portfolios | ✅ | AES-256 복호화 + corp_name JOIN |
| Portfolio | POST /portfolios | ✅ | Free 3종목 제한 |
| Portfolio | PUT /portfolios/{id} | ✅ | FE=PUT, BE=PUT 일치 (api_spec PATCH 불일치는 별도 이슈) |
| Portfolio | DELETE /portfolios/{id} | ✅ | |
| Stocks | GET /stocks/search | ✅ | |
| Notification | GET /notifications | ⚠️ | **R1**: List → PageResponse, 파라미터 추가 |
| Notification | GET /notifications/settings | ✅ | |
| Notification | PUT /notifications/settings | ✅ | |
| Notification | PATCH /notifications/{id}/read | ✅ | IDOR 방어 포함 |
| Notification | PATCH /notifications/read-all | ✅ | |
| Notification | GET /notifications/unread-count | ✅ | |
| Notification | POST /notifications/test | ✅ | |
| Disclosure | GET /disclosures | ✅ | scope/sentiment/withheld/from/to 필터 구현 |
| Disclosure | GET /disclosures/{id} | ⚠️ | **R2**: rcept_dt YYYYMMDD 포맷 |
| Disclosure | GET /disclosures/{id}/analysis | 📌 | similar_disclosures=null(Stage 3), financial_context=null(Stage 5) — 의도적 |
| Analysis | POST /analyses/{id}/feedback | ✅ | |
| Consent | GET /consents/status | ✅ | policy_version 비교 |
| Consent | POST /consents | ✅ | |
| Pricing | GET /pricing/plans | ✅ | PricingProperties yml 바인딩 |
| Checkout | UI mockup | 🚫 | payment-pg-integration.md 별도 Spec |

---

## 관련 패턴 / 과거 사례

- `PageResponse<T>` (`shared/dto/PageResponse.java`) — `DisclosureQueryService`에서 공시 목록 페이지네이션에 이미 사용 중. 동일 패턴을 알림에 적용.
- `NotificationRepository` — 배치용 `Pageable` 메서드가 이미 존재 (`findPendingByStatus`). 신규 메서드 추가 패턴 확인됨.
- `DateTimeFormatter.BASIC_ISO_DATE` — Java 표준 라이브러리. "20240622" 포맷 생성.
- `DART OpenAPI rcept_dt` — 원본이 YYYYMMDD(8자리) 형식. FE는 이 원본 형식 그대로 기대. BE 저장 시 LocalDate로 파싱했으므로 직렬화 단계에서 원복 필요.

---

## 리스크 / 법적 검토

| 리스크 | 심각도 | 대응 |
|--------|--------|------|
| `GET /notifications` 응답 형식 변경 → FE 기존 로직 재확인 필요 | P0 | FE `notifications.ts`가 이미 PageResponse 형식으로 파싱 — 즉시 호환 |
| `rcept_dt` 포맷 변경 → `GET /disclosures` 및 `/{id}` 동시 적용 필요 | P1 | DisclosureListItemResponse 단일 지점 수정으로 전체 통일 |
| Pageable sort 파라미터 주입 공격 (sort=password_hash,asc 등) | P1 | `@SortDefault` 고정 또는 허용 필드 화이트리스트(`created_at` 만 허용) |
| `similar_disclosures` null → FE Pro+ 업셀 CTA 의존 | P2 | FE 이미 null 처리 확인됨. Stage 3 Spec 별도 작성 시까지 현행 유지 |
| 자본시장법 | — | 변경 사항 없음. disclaimer 항상 포함 정책 영향 없음 |

---

## 권장 구현 방향

### R1 구현 (알림 페이지네이션)

```java
// NotificationController.java 변경 후
@GetMapping
public PageResponse<NotificationResponse> list(
        @AuthenticationPrincipal Long userId,
        @RequestParam(defaultValue = "0")  int page,
        @RequestParam(defaultValue = "20") @Max(100) int size
) {
    Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
    return notificationHistoryService.list(userId, pageable);
}
```

> sort 파라미터: MVP에서는 `created_at DESC` 고정 권장 (화이트리스트 없이 열면 정렬 필드 injection 위험).
> FE가 sort를 보내더라도 BE에서 무시하고 고정 정렬 적용 시 FE 동작에 영향 없음.

### R2 구현 (rcept_dt 포맷)

```java
// DisclosureListItemResponse.from() 수정
import java.time.format.DateTimeFormatter;

d.getRceptDt().format(DateTimeFormatter.BASIC_ISO_DATE)  // "20240622"
// LocalDate.toString() 제거
```

단일 라인 변경으로 GET /disclosures 목록 + 단건 전체 통일됨.

---

## 구현 순서 (의존성 없는 독립 작업)

| 순서 | Wave | 내용 | 파일 수 |
|------|------|------|---------|
| 1 | BE-1 | R2 rcept_dt 포맷 수정 | 1개 |
| 2 | BE-2 | R1 알림 페이지네이션 (Controller + Service + Repository) | 3개 |
| 3 | DOCS | R3 api_spec.md Stage 3 null 정책 명시 | 1개 |
| 4 | FE | R4 signup aria-label | 1개 |

Wave 1·2·4는 병렬 실행 가능 (상호 의존 없음).

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
