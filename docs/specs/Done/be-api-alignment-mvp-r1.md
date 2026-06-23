---
type: spec
status: Approved
created: 2026-06-22
updated: 2026-06-23
---

# BE/DB API 정합 MVP R1 Spec

> 상태: **Approved** (2026-06-23, dc-tech-review 승인)
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

---

## Tech Review (dc-tech-review · 2026-06-23)

> 검토 방식: api_spec 신뢰 금지, **BE Controller/Service/Repository/DTO 직접 Read 후 검증**
> (메모리 `feedback_be_read_before_fe_implement` 원칙). FE 소비측(`notifications.ts`,
> `disclosures/page.tsx`, `signup/page.tsx`)도 실제 코드 대조.

### 검증 결과 (Spec 주장 ↔ 실제 코드)

| Spec 주장 | 실제 코드 | 판정 |
|-----------|-----------|------|
| R1: `NotificationController.list()` = `List<NotificationResponse>` 반환 | `NotificationController.java:36` 확정 | ✅ 정확 |
| R1: FE `notifications.ts`가 PageResponse 파싱 → 즉시 호환 | `notifications.ts:55` = `apiClient<{ content: Notification[] }>` — **`.content`만 읽음, `page` 메타 미소비** | ⚠️ 부분정확 (아래 R1-주의) |
| R2: `DisclosureListItemResponse.rceptDt = d.getRceptDt().toString()` | `DisclosureListItemResponse.java:45` 확정 | ✅ 정확 |
| R2 card #3: `/disclosures/{id}` 별도 수정 필요 | `DisclosureController.java:68` detail()도 **동일 DTO `from()` 사용** | ✅ 단일 수정으로 자동 커버 |
| R2 card #2: 전역 Jackson `LocalDate` 설정 확인 필요 | `JacksonConfig` 없음 + `application.yml` date 설정 없음 + `rceptDt`는 **`String` 타입** | ✅ **영향 없음 — 카드 종결** |
| R3: `similar_disclosures`/`financial_context` 의도적 null | `AnalysisResponse.java:92,94` TODO 주석 확정 | ✅ 정확 |
| R4: `/signup` submit 버튼 `aria-label` 누락 | `signup/page.tsx:107` 확정 (텍스트 "가입하고 시작하기" 존재) | ✅ 정확 |
| FE `disclosures/page.tsx` YYYYMMDD 비교 | `disclosures/page.tsx:29` `replace(/-/g,"")` 확정 | ✅ 정확 (R2가 차단 원인) |

### 아키텍처 분해

- **영향 레이어**: backend(user/controllers·services, notification/repositories, disclosure/dto) / frontend(auth/signup) / docs(api_spec)
- **신규**: 없음 — 기존 클래스 시그니처 수정만. `PageResponse`(shared/dto) 재사용.
- **수정**: `NotificationController` · `NotificationHistoryService` · `NotificationRepository` · `DisclosureListItemResponse`(1줄) · `AnalysisResponse`(주석) · `signup/page.tsx`(1속성) · `api_spec.md`
- **Stage 파이프라인 영향**: 없음 (R3는 문서화만, 코드 변경 없음)

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | R2: `DisclosureListItemResponse.from()` rceptDt → `BASIC_ISO_DATE` (목록+상세 동시 커버) | backend/disclosure/dto | BE | 하 | - |
| 2 | R2-test: `DisclosureControllerTest` rcept_dt="20240622" 단언 추가/갱신 | backend/test | BE | 하 | #1 |
| 3 | R1: `NotificationRepository`에 `Page<NotificationEntity> findByUserId(Long, Pageable)` 추가 | backend/notification/repo | BE | 하 | - |
| 4 | R1: `NotificationHistoryService.list(Long, Pageable)` — **DB 페이지네이션 후 page 콘텐츠만 bulk-join** (아래 R1-주의 패턴) | backend/user/services | BE | 중 | #3 |
| 5 | R1: `NotificationController.list()` → `PageResponse<NotificationResponse>` + page/size 파라미터 + 검증 | backend/user/controllers | BE | 중 | #4 |
| 6 | R1-test: 알림 목록 PageResponse 구조 + 소유권 격리 통합 테스트 (Testcontainers) | backend/test | BE | 중 | #5 |
| 7 | R3: `AnalysisResponse` 주석 표준화 + `api_spec.md §2.4` Stage 3/5 null 정책 명시 | backend/analysis + docs | BE | 하 | - |
| 8 | R4: `/signup` submit 버튼 `aria-label="가입하고 시작하기"` | frontend/auth | FE | 하 | - |

> Spec 원안 대비 추가: **#2·#6 테스트 카드** (원안 누락 — CLAUDE.md §6-6 통합 테스트는 Testcontainers 필수, 응답 형식 변경은 회귀 위험).

### R1-주의: 페이지네이션 ↔ bulk-join 상호작용 (핵심 구현 포인트)

현재 `NotificationHistoryService.list()`는 ① 전체 조회 → ② `Disclosure`+`AnalysisResult` **bulk 조인(N+1 방지)** → ③ in-memory sort → ④ DTO 매핑 순서다. 페이지네이션 도입 시:

- ❌ `repo.findByUserId(userId, pageable).map(n -> NotificationResponse.from(...))` — `Page.map()`은 요소별 호출 → **bulk-join 깨지고 N+1 재발생**.
- ✅ 올바른 패턴:
  1. `Page<NotificationEntity> page = repo.findByUserId(userId, pageable)` (DB 레벨 정렬·페이징)
  2. `page.getContent()`에 대해서만 기존 bulk-join 수행 → `List<NotificationResponse> dtos`
  3. `PageResponse.from(new PageImpl<>(dtos, pageable, page.getTotalElements()))` 또는 `PageResponse`에 `(List<T> content, Page<?> meta)` 오버로드 추가
- in-memory `sorted(...)`는 **제거** — 정렬을 Pageable/DB로 이관 (정렬 일관성 + 페이지 경계 정확성).

### R1-주의: Repository 메서드명 ↔ Sort 충돌

Spec 원안 카드는 `findByUserIdOrderByCreatedAtDesc(userId, pageable)` 제안 + 권장 구현 예시는 `PageRequest.of(page, size, Sort.by("createdAt").descending())` 사용 → **메서드명 OrderBy와 Pageable Sort가 중복/충돌**. 택일:
- (권장) 메서드명 `findByUserId(userId, pageable)` + 정렬은 컨트롤러에서 `Sort` 고정 주입 — 정렬 정책이 한 곳.
- 또는 `...OrderByCreatedAtDesc` 유지 + Pageable에 Sort 미전달(`PageRequest.of(page, size)`).

### R1-주의: FE는 `.content`만 소비 (페이지 메타 미사용)

`notifications.ts:55`는 `{ content: Notification[] }`만 파싱하고 `page`(total_pages 등)는 읽지 않는다. 따라서:
- R1 적용 후 **FE는 즉시 정상화**(현재는 배열에서 `.content` 접근 → `undefined`로 깨진 상태, P0 확정).
- 단, FE에 **실제 페이지 이동 UI가 없음** → size=20 첫 페이지만 노출되는 한계 잔존. MVP 수용 가능하나, 무한스크롤/페이저는 **후속 FE 카드로 분리** 권장(본 Spec 범위 외, R1은 BE-only 유지).

### DB / 마이그레이션 영향

- **마이그레이션 불필요** — 검증 완료. `notifications` 복합 인덱스 `idx_notifications_user (user_id, created_at DESC)`가 `NotificationRepository:22` 주석에 명시·존재 → Pageable `ORDER BY created_at DESC` 쿼리 인덱스 커버.
- 신규 컬럼/인덱스 없음. R2는 직렬화 단계 포맷 변경(DB 저장값 `LocalDate` 불변).

### 외부 계약 영향

- 없음. DART rcept_dt 원본은 YYYYMMDD(8자리) → BE가 `BASIC_ISO_DATE`로 **원복**하는 것이므로 외부 계약과 오히려 정합. 카카오/OAuth/LLM 프롬프트 변경 없음.

### 리스크 & 법적 검토

- **[P1·보안] Pageable sort 인젝션** — FE가 `sort=password_hash,asc` 등 전달 시 정렬 필드 노출 위험. 대응: **sort 파라미터를 바인딩하지 않고** 서버에서 `created_at DESC` 고정(화이트리스트조차 불필요). FE가 sort 보내도 무시 → 동작 영향 없음.
- **[입력 검증] page/size 범위** — `size`는 `@Min(1) @Max(100)`, `page`는 `@Min(0)` + 컨트롤러 클래스에 `@Validated` 필요(원안 예시의 `@Max(100)` 단독으로는 미동작). 미설정 시 `size=10000` 등으로 메모리 압박.
- **[회귀] 응답 형식 변경** — R1은 공개 응답 envelope 변경. #6 통합 테스트로 구조 고정.
- **[자본시장법/개인정보]** 해당 없음 — disclaimer 정책·암호화 경로 변경 없음. 알림 이력은 회사명/sentiment만 노출(매수가·수량 비포함).

### 예상 wave 수

- **Wave A (BE, #1·#2)**: R2 rcept_dt + 테스트 — 독립, 최소 PR.
- **Wave B (BE, #3·#4·#5·#6)**: R1 알림 페이지네이션 + 테스트 — 의존 체인(repo→service→controller→test).
- **Wave C (DOCS+FE, #7·#8)**: R3 문서화 + R4 aria-label — 독립.
- A·B·C 상호 독립 → 병렬 가능. 권장 순서: B를 단일 PR로 묶고, A·C는 소형 PR 또는 B에 동봉.

### Spec 상태 전환 제안

검증 결과 **구현 가능**. 작업 카드·리스크·테스트 보강 완료 → `/dc-spec-move be-api-alignment-mvp-r1 Approved` 권장.
