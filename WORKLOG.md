---
type: worklog
status: active
created: 2026-06-02
updated: 2026-06-25
---

# WORKLOG

> 세션 단위 작업 기록. dc-push가 자동 갱신. dc-handoff의 데이터 소스.

---

## 2026-06-25 (48차) | topbar-global-search — TopBar 글로벌 검색 (종목명·공시 키워드)

**산출**:
- BE(수정): `backend/src/main/java/com/dartcommons/disclosure/controllers/DisclosureController.java` — `@RequestParam(required=false) @Size(max=100) String q` 파라미터 추가, 서비스 계층 전달
- BE(수정): `backend/src/main/java/com/dartcommons/disclosure/services/DisclosureQueryService.java` — `q` 파라미터 수신, `normalizedQ` 로컬 변수로 blank→null 정규화(Google Java Style 파라미터 재할당 금지), 4개 리포지토리 호출에 전달
- BE(수정): `backend/src/main/java/com/dartcommons/disclosure/repositories/DisclosureRepository.java` — 4개 쿼리에 q 조건 추가. JPQL: `:q IS NULL OR LOWER(d.reportNm) LIKE LOWER(CONCAT('%',:q,'%'))`. native: `CAST(:q AS text) IS NULL OR d.report_nm ILIKE '%' || :q || '%'` 패턴
- Test(수정): `backend/src/test/java/com/dartcommons/disclosure/DisclosureQueryServiceIntegrationTest.java` — `insertDisclosure` 5인자 오버로드 + q 키워드 일치/불일치/빈문자열 3케이스 추가
- FE(수정): `frontend/src/components/layout/TopBar.tsx` — 글로벌 검색창(form[role=search], Search 아이콘, Input) 추가. `useCallback` handleSearch, `onSubmit={e => e.preventDefault()}` 폼 기본 제출 차단, `encodeURIComponent` 안전 라우팅
- FE(수정): `frontend/src/lib/api/disclosures.ts` — `DisclosureListParams.q` 필드 추가, URLSearchParams 빌드 시 빈 문자열 q 생략
- FE(수정): `frontend/src/app/(app)/disclosures/page.tsx` — `DisclosuresFeedContent` 내부 컴포넌트 분리 + Suspense 경계 추가, `useSearchParams()` q 파라미터 구독, q 변경 시 page·allItems 리셋, 검색 결과 배너/빈 상태 메시지, Skeleton 폴백
- Spec: `docs/specs/Draft/topbar-global-search.md` → `docs/specs/Approved/topbar-global-search.md`
- DevLog: `docs/dev-log/backend.jsonl` + `frontend.jsonl`

**결정**:
- **JPQL vs native q null 처리 분기**: JPQL String 파라미터는 PostgreSQL OID 타입 추론 문제 없으므로 `:q IS NULL` 직접 사용. native 쿼리는 extended protocol parse 단계 OID 미지정 방지를 위해 `CAST(:q AS text) IS NULL` 패턴 필수 — 기존 `:fromDate`, `:sentiment` CAST 패턴과 동일한 이유.
- **Suspense 경계 위치**: `(app)/layout.tsx`에 Suspense 미존재 → `DisclosuresFeedContent` 내부 컴포넌트 분리 후 `DisclosuresFeedPage` default export에 Skeleton 폴백 Suspense 래핑. `portfolios/add/page.tsx` 선례 동일.
- **blank→null 정규화 위치**: Controller(입력 레이어) 대신 Service 계층에서 처리. `normalizedQ` 로컬 변수 사용 (Google Java Style: 파라미터 재할당 금지, Checkstyle ParameterAssignment 규칙).
- **LIKE 메타 문자 MVP 수용**: `%`, `_` 이스케이프 미처리. 이스케이프 레시피(`q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_") + ESCAPE '\\'`)는 `DisclosureRepository` 헤더 주석에 기록.
- **form onSubmit 차단**: `onSubmit={e => e.preventDefault()}` 필수 — `onKeyDown`의 `e.preventDefault()` + `e.stopPropagation()`만으로는 `<form>` Enter 기본 제출(페이지 리로드) 차단 불가.

**테스트**: Testcontainers PostgreSQL 통합 테스트 2케이스 추가 (q 키워드 일치/불일치, q 빈문자열). `dc-review-code` 8건(H1·M1~M4·L1~L3) 수정 후 Green 판정.

**미완료 (다음 세션)**:
- `/dc-implement portfolio-csv-bulk-import` — `docs/specs/Approved/portfolio-csv-bulk-import.md` 대기 중

---

## 2026-06-25 (47차) | deployment-infra-docker-cicd — M4 배포 인프라 Docker+CI/CD

**산출**:
- Infra(신규): `backend/Dockerfile` — JDK21 빌더→JRE21-alpine 런타임 멀티스테이지. `appuser` 비루트.
- Infra(신규): `frontend/Dockerfile` — node:22-alpine base→deps→builder→runtime 4스테이지. pnpm@9.15.9 base 스테이지 공용(중복 제거). `NEXT_PUBLIC_API_URL` ARG 빌드타임 주입.
- Infra(신규): `docker-compose.prod.yml` — postgres/backend/frontend 3서비스. bind mount `/home/ubuntu/data/dartcommons-pg`. 127.0.0.1 포트 바인딩. 메모리 제한(BE 1g, FE 512m). `dartcommons-net` 내부 네트워크. Ollama 주석 처리(Cloud LLM 사용 중).
- Infra(신규): `.env.prod.example` — `LLM_BASE_URL`·`OPENROUTER_API_KEY`·`NEXT_PUBLIC_API_URL` 등 실제 코드 SSOT 기준 키 일체. `CHANGE_ME_` 프리픽스.
- Infra(수정): `.gitignore` — `!.env.prod.example` 예외 추가.
- Infra(신규): `nginx/dartcommons.conf` — 80→443 redirect, `/api/`→BE:8080, `/`→FE:3000. HSTS(1년). TLS 1.2/1.3 + ECDHE. `ssl_session_tickets off`. Let's Encrypt ACME 위치.
- CI(신규): `.github/workflows/deploy.yml` — test(20m)→build-push GHCR(30m)→SSH deploy+헬스체크(15m). SHA 핀닝(`appleboy/ssh-action@0ff4204`). GITHUB_TOKEN EC2 주입 제거. 미사용 postgres service 제거. H1 롤백 로직→진단 로그+`exit 1`.
- Docs(신규): `docs/운영가이드.md` — EC2 프로비저닝·Docker설치·GHCR PAT 영구 로그인·SSL/HSTS·GitHub Secrets·수동배포·Flyway확인·DB백업·Ollama 옵션·장애대응.
- Spec: `docs/specs/Draft/deployment-infra-docker-cicd.md` → `docs/specs/Approved/` (git mv)
- DevLog: `docs/dev-log/backend.jsonl` + `frontend.jsonl`

**결정**:
- **`.env.prod.example` 파일명**: `.gitignore`의 `.env.*` 패턴이 `.env.prod.template`을 차단 — `.example`로 통일. `!.env.prod.example` 예외 추가.
- **Ollama 주석 처리**: `llm-production-switch`에서 OpenRouter Cloud LLM 전환 완료(커밋 08af22b) — EC2에 Ollama 불필요. t3.medium(4GB, ~$30/월) 가능. Ollama 필요 시 docker-compose.prod.yml 주석 해제 + t3.large 업그레이드.
- **H1 롤백 제거**: `compose down → up --no-recreate`는 같은 `:latest` 이미지 재시작으로 진정한 롤백 불가. 진단 로그 출력 + `exit 1`으로 GitHub 알림 트리거가 더 실용적. 진정한 롤백은 SHA 태그 추적 필요(후속 과제).
- **GITHUB_TOKEN EC2 주입 제거**: 워크플로 script body에 토큰 인라인 삽입 → EC2 프로세스 목록 일시 노출. 대신 EC2에 `read:packages` PAT를 `~/.docker/config.json`에 영구 저장(운영가이드 §2-5).
- **메모리 제한**: t3.medium(4GB)에서 BE 1g + FE 512m + postgres ~256m = 총 ~1.75g. 여유 ~2.25g. OOM 방지.
- **NEXT_PUBLIC_API_URL 빌드타임 고정**: Next.js `NEXT_PUBLIC_*` 변수는 빌드 타임 번들 삽입 — 런타임 env 주입 불가. CI `build-args`에서 GitHub Secrets `NEXT_PUBLIC_API_URL`로 주입. 도메인 변경 시 이미지 재빌드 필수.

**테스트**: `dc-review-code` 9항목 전체 수정(H1·M1~M4·L1~L4). Dockerfiles는 `docker build` 로컬 검증 미실시 — EC2 첫 배포 전 `docker build -t test ./backend` 권장(운영가이드 §3).

**미완료 (다음 세션)**:
- EC2 프로비저닝 + Docker 설치 (ops 수동 — 운영가이드 §2 체크리스트)
- GitHub Secrets 등록 (EC2_HOST, EC2_USER, EC2_SSH_KEY, NEXT_PUBLIC_API_URL)
- 도메인·SSL 설정 (CloudFlare 또는 certbot)
- EC2에서 `docker compose -f docker-compose.prod.yml up -d` 첫 수동 배포 + Flyway V1~V23 확인
- layout 컴포넌트 aria-label 미설정 8개 → 별도 이슈 등록 권장
- `Stage 3 RAG Chroma` — `docs/specs/Draft/analysis-stage3-rag-chroma.md` 대기 중

---

## 2026-06-25 (45차) | notification-pagination-fe — 알림 센터 더 보기 페이지네이션

**산출**:
- FE(수정): `frontend/src/lib/api/notifications.ts` — `NotificationPage` 인터페이스 추가(total_pages/total_elements snake_case, BE PageResponse @JsonProperty 정합). `useNotifications` 반환 타입 `apiClient<NotificationPage>`로 전환. `sort` 파라미터 제거(BE NotificationController가 createdAt DESC 고정, sort 바인딩 미지원).
- FE(수정): `frontend/src/app/(app)/notifications/page.tsx` — `allItems`/`currentPage` state 추가. `useEffect`로 페이지 누적(page=0이면 교체, 이후 append). 더 보기 버튼(`hasNext`, `isLoadingMore`, `Loader2` 스피너, `aria-busy`). 필터·읽음처리 모두 `allItems` 기준. 머리 주석 4종 완성.
- Spec: `docs/specs/Approved/notification-pagination-fe.md` (Draft→Approved 전환 완료)
- DevLog: `docs/dev-log/frontend.jsonl` — 페이지네이션 구현 기록

**결정**:
- **더 보기 버튼 방식 선택(무한스크롤 대신)**: 시니어 페르소나(C) 친화, IntersectionObserver 불필요, MVP 복잡도 최소화. 후속 전환 시 `setCurrentPage` 트리거만 교체.
- **읽음 처리 후 page=0 리셋**: optimistic update 미적용(MVP 수용). markAsRead/markAllAsRead 성공 시 첫 페이지로 복귀. 정밀 동기화는 후속 분리.
- **UNREAD 필터 범위 제한**: 서버 페이지네이션 + 클라이언트 필터 구조적 한계. 로드된 페이지 범위 내에서만 동작(MVP 수용). 서버 사이드 필터링은 후속.
- **total_pages snake_case**: Tech Review에서 api_spec.md(camelCase totalPages)와 BE 실제 코드(@JsonProperty "total_pages") 불일치 발견. BE 코드가 항상 SSOT — BE Read 우선 원칙 재확인.

**테스트**: `pnpm tsc --noEmit` 통과. dc-review-frontend: 종합 등급 B, P0 블로커 없음. 이번 변경 범위 내 접근성 위반 없음.

**미완료 (다음 세션)**:
- `/dc-tech-review deployment-infra-docker-cicd` — Docker/CI/CD 배포 인프라 (M4 크리티컬 패스, 7/3 런치)
- layout 컴포넌트 aria-label 미설정 8개 → 별도 이슈 등록 권장

---

## 2026-06-25 (46차) | llm-production-switch — OpenRouter 프로덕션 LLM 전환 + dc-review-code 8항목 수정

**산출**:
- BE(신규): `backend/src/main/java/com/dartcommons/infrastructure/llm/OpenRouterLlmClient.java` — `@ConditionalOnProperty(openrouter)`, `HostWhitelist.verify()`, Bearer 인증 헤더, `POST /chat/completions`, `choices[0].message.content` 파싱, `Stage2OutputRaw` private record, `@Retryable(3회/지수백오프 최대8s)`.
- BE(수정): `LlmProperties.java` — `apiKey` 필드 추가. `HostWhitelist.java` — `openrouter.ai` PROD_ALLOWED 추가. `OllamaLlmClient.java` — MAPPER FAIL_ON_UNKNOWN_PROPERTIES 수정. `application.yml` — LLM_BASE_URL·OPENROUTER_API_KEY·모델·타임아웃 갱신.
- Test(신규): `OpenRouterLlmClientTest.java` — 7케이스(정상·추가필드FAIL_ON_UNKNOWN·401·500·깨진JSON·잘못된sentiment·apiKey빈값) 모두 통과.
- Spec: `docs/specs/Approved/llm-production-switch.md` (Draft→Approved 전환 완료)
- DevLog: `docs/dev-log/backend.jsonl` — 구현 + 리뷰 수정 2건

**결정**:
- **MAPPER FAIL_ON_UNKNOWN_PROPERTIES=false (H1)**: LLM이 `reasoning` 등 추가 필드를 출력 시 `UnrecognizedPropertyException` → `@Retryable` 3회 90s 낭비 → 분석 영구 실패. `new ObjectMapper().configure(FAIL_ON_UNKNOWN_PROPERTIES, false)`로 수정. OllamaLlmClient도 동일 적용.
- **apiKey 생성자 즉시 검증 (M1)**: `provider=openrouter`에 `OPENROUTER_API_KEY` 미설정 시 재시도 3회 낭비 방지 → 생성자에서 `IllegalStateException` 던져 부팅 실패로 조기 발견.
- **CONNECT_TIMEOUT_MS=5_000 분리 (M3)**: Cloud LLM TCP 연결(5s)과 추론 대기(30s)를 분리. 네트워크 단절 조기 감지.
- **Stage2OutputRaw 독립 private record 유지**: OllamaLlmClient와 공유하지 않음. 두 파일만 존재하므로 DRY 비용보다 캡슐화 이득이 큼.
- **Usage·finishReason·role 미사용 필드 제거**: FAIL_ON_UNKNOWN_PROPERTIES=false로 JSON 무시 가능. 후속 wave 토큰 영속화 시 OpenRouterResponse.usage 재추가 필요.

**테스트**: `./gradlew test --tests OpenRouterLlmClientTest` → 7/7 BUILD SUCCESSFUL. Java 내장 `com.sun.net.httpserver.HttpServer`로 실제 HTTP 동작 검증(Spring AOP 없이 @Retryable 범위 밖 테스트).

**미완료 (다음 세션)**:
- `/dc-tech-review deployment-infra-docker-cicd` — Docker/CI/CD 배포 인프라 (M4 크리티컬 패스, 7/3 런치)
- layout 컴포넌트 aria-label 미설정 8개 → 별도 이슈 등록 권장

---

## 2026-06-24 (44차) | review-frontend-hover-capture — dc-review-frontend hover 상태 자동 캡처

**산출**:
- Skill(수정): `.claude/skills/dc-review-frontend/scripts/review-capture.js` — `captureHoverStates()` 추가. `data-pw-hover-idx` 속성 주입→`page.locator()` 안전 재선택 패턴(page.$$()[index] DOM 재정렬 위험 회피). viewport 내 visible 인터랙티브 요소(button/a[href]/[role='button']) 면적 상위 6개 자동 감지·hover()·200ms·boundingBox crop(+20px padding)·cleanup. `reviewPage()` 루프에 full-page 캡처 직후 통합. JSON 리포트에 `hoverCaptures` 배열 추가(`authMode`와 공존).
- Skill(수정): `.claude/skills/dc-review-frontend/references/checklist.md` — PC #8 "호버/인터랙션" 기준 `{name}-hover-{n}.png 캡처 이미지 기반 평가`로 개정.
- Skill(수정): `.claude/skills/dc-review-frontend/SKILL.md` — Phase 1 출력 파일 목록·report 구조 갱신.
- Spec: `docs/specs/Approved/review-frontend-hover-capture.md` (Draft→Approved, Tech Review 갱신 섹션 포함)
- DevLog: `docs/dev-log/frontend.jsonl` — hover 캡처 구현 기록

**결정**:
- **data-pw-hover-idx 안전 재선택 패턴**: `evaluate()`와 `$$()` 사이 DOM 변경 시 인덱스 어긋남 방지. `evaluate()` 내에서 선택과 동시에 속성 주입, 이후 `page.locator('[data-pw-hover-idx="n"]')`로 안전 선택. 캡처 후 `evaluate()`에서 일괄 cleanup.
- **6/22 Tech Review 이후 드리프트**: `review-frontend-auth-capture`(커밋 `432aa37`) 전면 재작성으로 `reviewPage()` 루프 구조·모바일 메뉴 토글 위치·JSON 리포트 필드 변경. 6/24 Tech Review갱신 섹션에서 보정 완료.
- **`.claude/` gitignore**: 스킬 스크립트 3개 로컬 전용 — Spec + dev-log만 커밋 대상.

**테스트**: 해당 없음 (스킬 스크립트 로컬 전용, 단위 테스트 대상 아님)

---

## 2026-06-24 (43차) | review-frontend-auth-capture — dc-review-frontend --auth 인증 모드 확장

**산출**:
- Skill(수정): `.claude/skills/dc-review-frontend/scripts/review-capture.js` — `loadEnvFile()`, `parseArgs(--auth)`, `loginAndGetState()`, `createAuthContext()` 추가. `none|sentinel|login:<e>:<p>|state:<path>` 4모드 지원. PC/Mobile 동일 쿠키 적용. JSON 리포트 `authMode` 필드 추가.
- Skill(수정): `.claude/skills/dc-review-frontend/SKILL.md` — Phase 1 `--auth` 옵션 표·5예시·sentinel·login 한계 주의사항 문서화.
- Root(신규): `.env.review.example` — `REVIEW_AUTH_EMAIL/PASSWORD/BE_URL` 템플릿 + 보안 주의사항
- Root(수정): `.gitignore` — `!.env.review.example` 예외 추가 (`.env.*` 패턴에 `.env.review` 포함됨)
- Spec: `docs/specs/Approved/review-frontend-auth-capture.md` (Draft→Approved, Tech Review 갱신 섹션 추가)

**결정**:
- **sentinel 모드 전제 재확인**: middleware E4 변경(2026-06-17) 이후 `/pricing`은 `PUBLIC_EXACT`로 여전히 무조건 통과. `(public)/layout.tsx:15` presence 판정도 유지. sentinel 모드 핵심 목적(네비바 CTA 검증) 영향 없음.
- **bug correction ①②③ 적용**: BE 경로(`/api/proxy/auth/login` → `${beUrl}/auth/login`), browser 파라미터 수신(내부 launch() 금지), dotenv 직접 파싱.
- **login 모드 신규 제약 반영**: 테스트 계정 `onboarding_completed=true` 필수. middleware E4 드리프트 카드 #2 보정.
- **`.claude/` gitignore**: 스킬 스크립트는 로컬 전용 — git 미추적. Spec + dev-log + `.gitignore` + `.env.review.example`만 커밋 대상.

**테스트**: 해당 없음 (스킬 스크립트 로컬 전용, 단위 테스트 대상 아님)

---

## 2026-06-24 (42차) | frontend-share-card-image + share-card-capture-playwright-test — 공유 카드 이미지 캡처 + Playwright E2E 6건

**산출**:
- FE(수정): `frontend/src/app/(app)/share/page.tsx` — html2canvas-pro 기반 PNG 캡처·다운로드·모바일 파일 공유 완성. `captureShareCard()` (`document.fonts.ready` 대기 → `toBlob`), `triggerDownload()` (Firefox DOM-append 패턴), `handleShareImage()` (`canShare→share→download` 3단 폴백). `capturing` 상태 기반 두 버튼 `disabled`+`aria-busy`, `role="status"` aria-live 접근성.
- FE(신규): `frontend/e2e/helpers/session.ts` — Playwright 공통 세션 픽스처 (fake JWT, `setAuthCookie`, `mockMeResponse` — 민감정보 미포함)
- FE(신규): `frontend/e2e/share/capture.spec.ts` — Playwright E2E 6건: T1(파일명·크기), T2/T3(DOM 색상 비-블랙), T4(disabled·aria-busy 상태), T5(share 미지원→다운로드 폴백), T6(AbortError 에러 토스트 미발화). 5/5 GREEN (T4 포함)
- FE(의존성): `frontend/package.json` + `pnpm-lock.yaml` — `html2canvas-pro 2.2.0` 추가
- Spec: `docs/specs/Approved/frontend-share-card-image.md` (Draft→Approved)
- Spec: `docs/specs/Approved/share-card-capture-playwright-test.md` (신규 Approved)

**결정**:
- **html2canvas-pro 채택 이유**: Tailwind 4의 `oklch()` + CSS 변수(`--color-brand-navy`)를 구버전 html2canvas가 검정/투명으로 렌더링하는 P0. 활성 포크인 html2canvas-pro 2.2.0이 유일한 현실적 선택. satori/`@vercel/og`도 oklch 미지원 + 기존 컴포넌트 재사용 불가로 기각.
- **Firefox triggerDownload 패턴**: `document.body.appendChild(a) → click → removeChild → setTimeout(revoke, 1000)`. Firefox는 DOM에 없는 `<a>`의 click을 무시하는 경우 있음. `setTimeout(1000)`은 다운로드 시작 전 revoke 방지.
- **T4 addInitScript 전략**: 헤드리스 Chromium에서 html2canvas가 너무 빠르게 완료 → transient `capturing=true` 상태를 Playwright가 못 잡음. `document.fonts` 을 2초 지연 Promise로 override해 안정적인 검증 윈도우 확보.
- **catch-all BE mock 등록 순서**: Playwright last-registered-first-matched 규칙에 따라 catch-all(`${BE_BASE}/**`)을 먼저 등록(낮은 우선순위), 특정 mock을 나중에 등록(높은 우선순위로 override). AppShell 미모킹 BE 호출로 인한 `/login` 리다이렉트 차단.

**미완료**:
- R5 `GET /users/me/share-summary` 실 API — 현재 클라이언트 집계 유지. 별도 Spec/wave로 분리 예정.

**테스트**: Vitest 44/44 GREEN | Playwright 5/5 GREEN

---

## 2026-06-24 (41차) | portfolio-csv-upload + csv-euckr-binary-test — CSV 일괄 등록 + EUC-KR 바이너리 테스트

**산출**:
- FE(신규): `frontend/src/lib/csv/parsePortfolioCsv.ts` — EUC-KR/UTF-8-BOM 이중 디코딩 + `/\b\d{6}\b/g` 종목코드 추출. 종목코드만 반환, 매수가·수량 추출 없음(CLAUDE.md §7)
- FE(신규): `frontend/src/lib/csv/parsePortfolioCsv.test.ts` — Vitest 44케이스: 기본 4 + 인코딩 2 + EUC-KR 바이너리 2(skipIf 가드) + 경계값 6. 44/44 GREEN
- FE(수정): `frontend/src/app/(app)/portfolios/new/page.tsx` — CSV 드래그앤드롭 업로드 존 + 리뷰 패널 + apiClient 직접 순차 POST + 단일 invalidateQueries
- Spec: `docs/specs/Approved/portfolio-csv-upload.md` (Draft→Approved→이번 커밋)
- Spec: `docs/specs/Approved/csv-euckr-binary-test.md` (신규 Approved)

**결정**:
- **방향 A(FE 단독)** 채택: BE 엔드포인트 변경 없이 기존 `POST /portfolios` 순차 호출. N번 invalidateQueries 문제는 apiClient 직접 호출 + 루프 후 단일 invalidate로 해결.
- **EUC-KR 바이너리 ICU 가드**: `eucKrSupported` IIFE + `it.skipIf(!eucKrSupported)` 패턴으로 small-icu Node.js 빌드에서 false failure 방지.
- **0xA1 0xA1 선택 이유**: KS X 1001 이상적 공백(U+3000), EUC-KR 유효 범위(0xA1~0xFE) 2바이트로 TextDecoder 성공 경로를 실제 바이너리로 검증.
- **0xFF 선택 이유**: EUC-KR 유효 범위 초과 → `fatal: true` throw 즉시 유발 → UTF-8 폴백 경로 검증.
- **skippedFailed 컬렉션**: 비-ApiException 에러(네트워크 타임아웃 등)를 무음 삼키지 않고 toast에 "N 오류" 포함(dc-review-code High 수정).
- **csvPhase !== "idle" 가드**: handleDrop에서 parsing 중 중복 drop 차단(dc-review-code Medium 수정).

**테스트**: Vitest 44/44 GREEN

---

## 2026-06-24 (40차) | krx-job-test-isolation — KRX 잡 테스트 격리 + B128 HTTPS 전환

**산출**:
- BE(수정): `KrxPriceSyncJob.java` — `@ConditionalOnProperty(price-sync.enabled, matchIfMissing=true)` 추가
- BE(수정): `src/main/resources/application.yml` — `dartcommons.krx.price-sync.enabled: true` 추가
- BE(수정): `src/test/resources/application.yml` — `dartcommons.krx.price-sync.enabled: false` 전역 추가
- BE(수정): `KrxPriceSyncJobIntegrationTest.java` — `@TestPropertySource`에 `enabled=true` override 추가
- BE(수정): `KrxClient.java` — `B128_URL` http→https 전환 (curl 200 실측)

**결정**:
- 전역 test disable 전략 채택: Spec 원안(PortfolioIntegrationTest 단건)보다 test yml 전역 1줄이 유지보수·정합 우수. 신규 `@SpringBootTest` 클래스도 자동 적용됨.
- B128 HTTPS: curl 200 확인 즉시 전환 완료. 별도 조사 불필요.

**테스트**: 166/166 GREEN (Testcontainers)

---

## 2026-06-24 (39차) | eval-pnl-integration-tests — 통합 테스트 17케이스 + isValidPrice package-private

**산출**:
- BE(수정): `KrxClient.java` — `isValidPrice()` `private` → package-private (Option C, KrxClientTest 직접 호출)
- BE(수정): `PortfolioIntegrationTest.java` — R1 summary 6케이스 추가 + `@BeforeEach resetStockPrices()`(close_price NULL + 캐시 clear) + `CacheManager` 주입
- BE(신규): `KrxPriceSyncJobIntegrationTest.java` — R2 3케이스(updatesClosePrice·emptyMap·evictsCache) + R2-추가 2케이스(anomalySkip·nullPrevAllowed) = 5케이스
- BE(신규): `KrxClientTest.java` — R3 isValidPrice 경계값 6케이스(null·0·-1·0.99·1·60000)
- 전체 **166/166** 통과 (기존 149 + 신규 17)

### 결정 (코드에 드러나지 않는 사항)
- **isValidPrice Option C**: 메서드를 package-private으로만 완화해 직접 호출. `KrxClient` 생성자가 RestClient를 내부 빌드하므로 `MockRestServiceServer` 바인딩 불가 → 순수 단위 테스트 대신 `@SpringBootTest` 컨텍스트에서 직접 메서드 접근.
- **캐시 evict 테스트 가격 선택**: `syncPrices_evictsCache_freshPriceServedNextQuery`에서 60000→75000(+25%)을 사용. 50% 이상치 필터를 통과해야 실제 DB 갱신이 일어나기 때문. 100000 등 2배 값은 이상치로 스킵돼 DB가 안 바뀌어 캐시 evict를 검증할 수 없음.
- **@BeforeEach resetStockPrices**: stocks 테이블의 close_price는 DB 전체를 NULL로 초기화 + Caffeine stockByCode·stocksByCodeIn 캐시도 함께 clear. JDBC bypass로 캐시에 스테일 값이 남는 것을 방지. 기존 8케이스는 close_price를 읽지 않으므로 영향 없음.
- **anomaly 경계 수치**: 4999원 → 전일 10000 대비 50.01% 변동 → 스킵. 6000원 → 40% 변동 → 허용. `ANOMALY_THRESHOLD=0.5` 기준.

### 미완료 → 다음 세션
- `krx-job-test-isolation` Spec Draft — `KrxPriceSyncJob @ConditionalOnProperty` 누락 + B128 HTTP→HTTPS URL 조사. 본 Spec의 @Scheduled 격리 리스크와 연관.
- `be-api-alignment-mvp-r1` Spec Draft — 알림 페이지네이션 P0 + rcept_dt 형식 P1 수정 대기.

---

## 2026-06-24 (38차) | krx-price-source-resilience — KRX 종가 이상치 2단 방어

**산출**:
- BE(수정): `KrxClient.java` — `isValidPrice()` 헬퍼(1원 미만 절대 필터) + `fetchClosePricesFromKrx()`·`fetchClosePricesFromGithubCache()` 두 루프에 적용 + 스킵 카운터 + NFE per-stock WARN 추가
- BE(수정): `KrxPriceSyncJob.java` — `ANOMALY_THRESHOLD(±50%)` 상수 + `syncPrices()` 전일 대비 상대 이상치 스킵 + anomalySkipped WARN 로그
- BE(주석): `HostWhitelist.java` — `externalRestClient` URL 컴파일 상수 고정·동적 입력 금지 명시 (R2 Option B)
- 문서: `krx-price-source-resilience` Spec Draft→Approved (Tech Review 포함)
- 문서: `eval-pnl-integration-tests` Spec — R2-추가(이상치 통합 2케이스)·R3(isValidPrice 단위 6케이스) 보강
- 문서: `docs/ideas/issues/krx-anomaly-filter-test-coverage.md` 신규 이슈 파일 생성

**결정**:
- 2단 방어 레이어 분리: `KrxClient`(infra, 절대 필터) vs `KrxPriceSyncJob`(stocks, 상대 필터). `StockRepository`가 infra에 노출되면 CLAUDE.md §3-2 import 역방향 위반 → `findAll()` 이미 실행 중인 KrxPriceSyncJob에 상대 필터 배치로 추가 DB 비용 없이 경계 준수.
- ±50% 임계 선택: 정상 상한가/하한가(±30%) 통과 + 액면분할·합병 false positive 허용 (WARN 로그로 추적, 배치 실패 처리 안 함)
- R2 Option B 유지: `raw.githubusercontent.com`을 HostWhitelist에 추가하지 않음. GitHub 도메인 전체 허용(광범위) 트레이드오프 > 컴파일 상수 고정으로 SSRF 방어 충분.

**미완료**:
- `eval-pnl-integration-tests` — R1(summary 6케이스)·R2(배치잡 통합)·R2-추가(이상치 통합)·R3(isValidPrice 단위) 미구현. `/dc-tech-review eval-pnl-integration-tests` 후 구현 예정.
- `krx-job-test-isolation` — `@ConditionalOnProperty` 표준화 + B128 HTTP→HTTPS 조사 Draft 상태.

---

## 2026-06-24 (37차) | dashboard-eval-pnl — 평가손익 카드+KRX 종가 동기화+버그3건 픽스

**산출**:
- BE(신규): `KrxPriceSyncJob.java` — 일배치 종가 동기화 (`@Scheduled` + `@ConditionalOnProperty` 기본 false)
- BE(신규): `StockPriceProvider.java` / `StockPriceService.java` — 종가 조회 seam (Caffeine 캐시 경유)
- BE(신규): `V23__add_price_to_stocks.sql` — stocks 테이블에 `close_price`, `price_asof` 컬럼 추가
- BE(수정): `KrxClient.java` — `fetchAllClosePrices()` 추가 (KRX MDCSTAT01501 직접 → GitHub cache CSV 폴백), B128.bld 최근 거래일 조회
- BE(수정): `Stock.java` — `closePrice`, `priceAsof` 필드 추가 (V23 대응)
- BE(수정): `PortfolioService.java` — `summarize()`: 전 포트폴리오 평가손익 집계 (복호화 후 계산, 중간값 로그 금지)
- BE(수정): `PortfolioController.java` — `GET /api/portfolios/summary` 엔드포인트
- BE(버그픽스): `DisclosureRepository.java` — PostgreSQL null 파라미터 타입추론 오류 수정 (JPQL: COALESCE, native: CAST 패턴)
- BE(버그픽스): `StockMasterService.java` — SpEL 캐시 키 `T(Type).new()` → `new Type()` 수정
- Test(수정): `DisclosureControllerTest.java` — FREE tier 날짜 강제 정합 (오늘 날짜 사용, 149/149 Green)
- FE(신규): `StatCards.tsx` — `PnlStatCard` 컴포넌트 (한국 컨벤션 상승=빨강 ▲/하락=파랑 ▼, 万/億 컴팩트 표기, WCAG AA)
- FE(신규): `portfolios.ts` — `usePortfolioSummary()` TanStack Query 훅
- FE(수정): `dashboard/page.tsx` — `PnlStatCard` 실데이터 연동
- FE(수정): `dashboard/preview/page.tsx` — 목업 `PnlStatCard` 추가
- Spec: `dashboard-eval-pnl.md` Draft→Approved; 신규 Draft 3개 (`eval-pnl-integration-tests`, `krx-price-source-resilience`, `krx-job-test-isolation`)

### 결정
- **KRX 종가 2-hop 폴백**: KRX 직접 접근이 환경에 따라 차단될 수 있어 GitHub cache CSV(`FinanceDataReader/fdr_krx_data_cache`)를 폴백으로 채택. MVP 허용 범위; 장기 대안은 `krx-price-source-resilience` Spec에 위임.
- **StockPriceProvider seam**: `KrxClient`를 `PortfolioService`에 직접 주입하지 않고 인터페이스 경유. Stage 5 착수 시 시계열 테이블 기반으로 교체 가능(이 클래스 변경 최소화).
- **KrxPriceSyncJob ConditionalOnProperty**: `krx.price-sync.enabled=false`(기본값) — 운영 비활성화 방어. 운영 활성화 시 환경변수 투입 필요.
- **PostgreSQL null 타입 추론 패턴 확립**: JPQL은 `COALESCE(:param, col)`, native는 `CAST(:param AS type)` — DisclosureRepository 헤더 주석으로 메커니즘 문서화.
- **복호화 중간값 로그 금지**: `PortfolioService.summarize()` 주석에 명시. summarize()는 집계값(총손익/수익률)만 반환, 종목별 매수가 재노출 없음.

### 미완료
- `eval-pnl-integration-tests` Spec — `PortfolioService.summarize()` + `KrxPriceSyncJob` Testcontainers 통합테스트 (Draft 생성 완료)
- `krx-price-source-resilience` Spec — 이상치 필터(±30% WARN+무시) + GitHub cache 공급망 리스크 감사 (Draft 생성 완료)
- `krx-job-test-isolation` Spec — `@ConditionalOnProperty` 표준화 + `@TestPropertySource` 세트 (Draft 생성 완료)

---

## 2026-06-24 (36차) | 알림채널 dev안정화·이메일폴백 정합·설정저장토스트 (kakao-notification-channel Wave 1)

**산출**:
- BE(수정): `KakaoAlimtalkClient.java` — R1: `send()` placeholder dev 모드 분기 추가(`isDevMode()` 헬퍼 사용, sendOtp 패턴 통일). L1 픽스: `sendOtp()` 인라인 check → `isDevMode()` 헬퍼로 통일.
- BE(수정): `ChannelSender.java` — L2 픽스: `sendKakao()` 내 dev mode SENT 기록 동작 주석 추가.
- Infra(수정): `.env.example` — R2: Kakao 변수명 5종 yml 정합(`APP_KEY→API_KEY`, `KAKAO_SENDER_KEY` 등) + `MAIL_*` 7종 추가(MailHog 기본값 + Gmail/MailHog 안내 주석).
- FE(수정): `frontend/src/lib/api/notifications.ts` — R3: `useUpdateNotificationSettings` `onSuccess` → `toast.success("알림 설정이 저장됐습니다.")` 추가.

### 결정
- **R5(알림이력 FE) no-op 확인**: `notifications/page.tsx` 이미 `useNotifications()` 연결 완료. Spec 작성(2026-06-16) 이후 구현됨.
- **isDevMode() 헬퍼 통일**: `sendOtp()` 인라인 check와 `send()` 헬퍼 혼재 → 전부 `isDevMode()` 로 통일. dev mode 판단 기준이 단일 포인트.
- **Wave 3(카카오 비즈채널) 연기**: 비즈채널 승인 + 알림톡 템플릿 심사 외부 의존. MVP는 이메일 폴백 채널 기반으로 Go/No-Go 통과.

### 미완료
- R4(이메일 E2E 흐름 수동 검증): SMTP 실환경 or MailHog 연동 후 `AnalysisCompletedEvent` 수동 트리거 → `notification_logs.status=SENT` 확인. 별도 수동 검증 또는 `/dc-test-verify` 단계.
- R6~R8(카카오 비즈채널·템플릿·endpoint): 외부 승인 후 Wave 3.

---

## 2026-06-23 (35차) | 포트폴리오 캐시·NFE방어·FE검증 정리 (portfolio-review-followup Wave 1+2)

**산출**:
- BE(수정): `CacheConfig.java` — `stockByCode`(TTL 4h, maxSize 1000)·`stocksByCodeIn`(TTL 4h, maxSize 500) `registerCustomCache` 추가
- BE(수정): `StockMasterService.java` — `findByStockCode/@Cacheable`·`findByStockCodeIn/@Cacheable` 위임 메서드 추가; `sync()`에 `@CacheEvict(allEntries=true)` 추가; TreeSet SpEL 키 전제(6자리숫자)·@CacheEvict+@Transactional 순서 주석 추가
- BE(수정): `PortfolioService.java` — `StockRepository→StockMasterService` 전환(R2); 단건 `toResponse` 오버로드 제거(R1); `parseSafe()` NFE방어 헬퍼(R3); `Collectors.toMap` merge(R4); user→stocks 의존 근거 주석 추가
- FE(수정): `portfolios/add/page.tsx` — `avg_buy_price` max(999_999_999)·`quantity` max(100_000_000) 검증 추가(R5); handlePriceStep/handleQuantityStep 상한 클램핑
- FE(삭제): `PortfolioListItem.tsx` — dead component (import 0건, portfolios/page.tsx 대시보드 개편 후 미참조)
- Docs: `docs/issues/portfolio-review-cache-parsesafe-test.md` (P2)

### 결정
- **StockMasterService 위임 패턴**: JpaRepository `findById()`에 `@Cacheable` 직접 부착 불가(기본 메서드) → StockMasterService 위임(AnalysisResultCacheService 동일 패턴). PortfolioService 주입을 `StockRepository→StockMasterService`로 전환.
- **stocksByCodeIn 캐시 키**: `TreeSet.toString()` — 입력 순서 무관 안정 키. 종목코드 6자리숫자 전제(충돌 없음) 주석 명시.
- **@CacheEvict+@Transactional 순서**: Spring Boot 3.x 기본 프록시 순서("Cache 바깥, Transaction 안")에서 보장되지만 `@Order` 미명시. 분기 배치라 실위험 무시. 주석으로 기록.
- **PortfolioListItem.tsx 삭제**: import 0건 확인 후 삭제. dead code 유지보수 부담 제거.

### 미완료
- `PortfolioIntegrationTest` 캐시 hit/miss 검증 (A-1·A-2) → `docs/issues/portfolio-review-cache-parsesafe-test.md` (P2)
- `parseSafe()` NFE 경로 단위 테스트 (B-1·B-2) — package-private 추출 후 → 동일 이슈 파일 (P2)
- `portfolio-review-followup` Approved → 구현 완료. 다음 push 후 Done 전환 가능.

---

## 2026-06-23 (34차) | 투자 경험·주 사용 시점 DB 저장 활성화 (user-profile-investment-experience Wave 1+2)

**산출**:
- BE(신규): `V22__add_profile_fields_to_users.sql` — `investment_experience`(VARCHAR 15, nullable, CHECK) + `preferred_time`(VARCHAR 10, nullable, CHECK) 컬럼 추가
- BE(수정): `UserEntity.java` — `InvestmentExperience`·`PreferredTime` enum 추가, 필드 2개(`@Enumerated STRING`), `updateProfile()` null-safe 메서드 추가, 클래스 주석 V22 반영
- BE(수정): `UpdateMeRequest.java` — `nickname` `@NotBlank` → nullable(`@Size(min=1)` 유지), `investmentExperience`·`preferredTime` String 필드 추가(`@Pattern` 검증), DTO 주석 null/"" 경계 명시
- BE(수정): `UserMeResponse.java` — `investment_experience`·`preferred_time` 응답 필드 추가(null 허용), `from()` 팩토리 null-safe 처리
- BE(수정): `UserService.java` — `updateMe()` nickname null-safe 분기 + enum 파싱 후 `updateProfile()` 호출
- BE(수정): `UserController.java` — stale 주석 정정(V22 필드 반영)
- FE(수정): `authStore.ts` — `AuthUser` 타입에 `investment_experience`·`preferred_time` optional 필드 추가
- FE(수정): `auth.ts` — `UpdateMeBody.nickname` optional 전환, 두 필드 추가. 스테일 주석 "BE 미지원" 제거
- FE(수정): `signup/profile/page.tsx` — `useUpdateMe()` 호출 복원(nickname 미전송, 미선택 → undefined 변환, `isPending` 로딩, `try/catch + toast.error` 추가)
- Docs: `docs/issues/user-profile-v22-integration-test.md` 신규 (P2)

### 결정
- **nickname nullable 채택**: `@NotBlank` 제거. profile 단계에서 page refresh 시 `authStore.user=null` → nickname 미전송 안전(BE null → skip). 기존 마이페이지 닉네임 변경은 nickname 포함 전송으로 하위 호환.
- **@Pattern 컨트롤러 검증**: enum 잘못된 값은 컨트롤러 단계 400. 서비스 `valueOf()` 실패 경로 없음.
- **빈 문자열 → undefined 변환 (FE)**: `(experience || undefined)` — JSON 직렬화 시 필드 생략 → BE null 수신 → skip. null과 "" 혼동 방지.
- **investment_experience 용도 제한**: DB·코드·주석 전 레이어에 "투자 권유 판단 금지, 해석 복잡도 조정 전용" 명시 (통합기획서 §11.1).

### 미완료
- `PATCH /users/me` V22 시나리오 통합 테스트 미작성 → `docs/issues/user-profile-v22-integration-test.md` (P2)
  5가지 시나리오: nickname 없이 두 필드만, mixed, 잘못된 enum 400, 빈 nickname 400, GET 응답 필드 확인

---

## 2026-06-23 (33차) | 대시보드 오늘 필터 + Free 5건/일 강제 (dashboard-real-data Wave 1+2)

**산출**:
- BE(수정): `DisclosureQueryService.java` — `tier==FREE` 분기: `fromDate/toDate=LocalDate.now(ZoneId.of("Asia/Seoul"))` 강제, `page=0`, `size=Math.min(size,5)`. ZoneId import 추가. 403 체크를 Free 블록 위로 이동(리뷰 Medium 즉시 수정).
- FE(수정): `dashboard/page.tsx` — `useDisclosures`에 `from/to=오늘(Intl.DateTimeFormat sv, Asia/Seoul)` 추가. outdated 주석 정정("W4 교체 예정" 제거). `isFreeLimited(total_elements>5&&FREE)` 배너 추가 + ProUpsellModal CTA.
- Docs: issues 2건 신규 (`disclosure-free-tier-enforcement-test.md`, `dashboard-today-midnight-staleness.md`)

### 결정
- **Free 강제 메커니즘**: size 클램핑만으로는 `page=1,2…` 페이지네이션 우회 가능. 오늘+page0+5건 강제(3중) 채택.
- **`total_elements` 활용**: `free_limit_reached` 신규 필드 불필요 — `total_elements > 5`로 클라이언트가 추론.
- **타임존 기준**: BE `ZoneId.of("Asia/Seoul")`, FE `Intl.DateTimeFormat("sv", {timeZone:"Asia/Seoul"})` 동일 기준. Free는 BE가 항상 오늘 강제(FE 전달값 무시).

### 미완료
- `DisclosureQueryService` Free 강제 Testcontainers 통합 테스트 미작성 → `docs/issues/disclosure-free-tier-enforcement-test.md` (P2)
- `today` 자정 고착 엣지케이스(Pro/Premium) → `docs/issues/dashboard-today-midnight-staleness.md` (P3, `useTodaySeoul` 훅 방향)

---

## 2026-06-23 (32차) | OAuth 동의 데이터 정합 + 좀비 계정 배치 정리 (oauth-consent-data-integrity)

**산출**:
- BE(신규): `V21__nullable_agreed_at.sql` — `users.terms_agreed_at / privacy_agreed_at` NOT NULL 해제 + COMMENT
- BE(수정): `UserEntity.java` — `@Column(nullable = true)` 전환, 클래스 주석 V21 반영
- BE(수정): `AuthService.java` — `autoSignup()` `.termsAgreedAt(now)` `.privacyAgreedAt(now)` 빌더 호출 제거, 클래스 주석 정정
- BE(수정): `ConsentService.java` — stale 주석("OAuth 로그인마다" → "POST /me/oauth-consent 멱등 체크 시") 정정
- BE(수정): `ConsentLogRepository.java` — stale 주석 동일 정정
- BE(수정): `UserRepository.java` — `findIncompleteOAuthAccountIds` + `softDeleteByIdIn(@Modifying clearAutomatically=true)` 신규
- BE(수정): `RefreshTokenRepository.java` — `deleteByUserIdIn` 벌크 삭제 신규
- BE(신규): `OAuthIncompleteAccountCleanupJob.java` — `@Scheduled(cron="0 0 3 * * *")`, `@ConditionalOnProperty`, soft delete 배치

### 결정 (코드에 드러나지 않는 사항)
- **E3 수정 범위**: `autoSignup()`만 수정. `signup()`(이메일 가입)은 동의와 동시 발생 — `now()` 정합 유지, 변경 금지.
- **M-P2 Drop**: `oauthCallback`이 V20 이후 `onboarding_completed_at` 기준으로 전환되어 `hasRequiredConsents()` 매 로그인 호출이 없어짐. 캐싱 전제 소멸 → Wave 2 전체 제거.
- **M-M2 삭제 기준 변경**: `hasRequiredConsents()=false` → `onboarding_completed_at IS NULL` (더 정확한 미완료 지표).
- **soft delete 확정**: `consent_logs` INSERT-only 보존 의무(통합기획서 §11.1) — hard delete 금지. 좀비 계정 `deleted_at` 설정으로 인증 흐름 제외.
- **마이그레이션 V21**: Spec 원안 "V19"는 V20이 이미 존재하여 사용 불가(Flyway out-of-order 방지). V21 사용.
- **FE 변경 불필요 확인**: `authStore.ts:27-28` `terms_agreed_at?` 이미 optional 타입, UI 렌더링 없음.

### 미완료 (다음 세션 이어갈 작업)
- **테스트 미작성**: `OAuthIncompleteAccountCleanupJob` 통합 테스트(Testcontainers) 미포함. 구현 후 별도 파이프라인에서 추가 권장.
- **Spec 완료 처리**: `/dc-spec-move oauth-consent-data-integrity Done` 대기 (다음 `/dc-push` 시).

---

## 2026-06-23 (31차) | 포트폴리오 알림 토글 dead code 제거 (portfolio-management-e2e R3)

**산출**:
- FE(수정): `portfolios/add/page.tsx` — `DISCLOSURE_TYPES` 상수·`notifTypes` state·`Switch`·`FileText/BarChart2/Users` import 제거. 알림 설정 callout + `/notifications/settings` 링크로 교체.
- Docs: `docs/dev-log/frontend.jsonl` — 변경 기록 추가.

### 결정 (코드에 드러나지 않는 사항)
- **R3 Option A 채택**: per-stock notify_enabled는 BE 미구현 상태. MVP에서 UX 가치 불명확하여 계정 전역 알림(`/notifications/settings`)으로 일원화. 토글이 UI에만 존재하고 실제 전송되지 않는 혼동 제거.
- **Option B 전환 경로 보존**: `[수정 시 고려사항]` 주석에 V19 마이그레이션 참조 명시 — 향후 per-stock 알림 지원 시 진입점 확보.

---

## 2026-06-23 (30차) | Caffeine 캐시 인프라 + staleTime 정책 (performance-caching-staletime)

**산출**:
- BE(신규): `CacheConfig.java` — `@EnableCaching` + `CaffeineCacheManager`, `portfolioStockCodes`(5m/1k) + `analysisResult`(10m/10k) per-cache 등록
- BE(신규): `AnalysisResultCacheService.java` — `@Cacheable("analysisResult")` 전담 빈, `null`-반환 메서드로 Spring Data Optional 언랩 문제 우회
- BE(수정): `UserStockCodesProviderImpl.java` — `@Cacheable("portfolioStockCodes", key="#userId")`
- BE(수정): `PortfolioService.java` — `@CacheEvict("portfolioStockCodes")` on create/delete
- BE(수정): `AnalysisQueryService.java` — `AnalysisResultCacheService` 경유로 전환, `@Transactional` 제거(캐시 서비스에서 처리)
- BE(수정): `DisclosureQueryService.java` — `size = Math.min(size, 100)` 이중 방어
- FE(수정): `providers.tsx` — 전역 `staleTime:60_000` 제거
- FE(수정): `disclosures.ts` — list:60s, detail:5m+noRefetch, analysis:5m+noRefetch
- FE(수정): `notifications.ts` — list:30s, settings:5m
- FE(수정): `portfolios.ts` — list:2m+refetchOnFocus
- Spec: `performance-caching-staletime` Draft→Approved, Tech Review 추가

### 결정 (코드에 드러나지 않는 사항)
- **AnalysisResultCacheService 분리 빈 패턴 채택**: Spring Data JPA `Optional` 반환 메서드에 `@Cacheable` 직접 적용 시 Spring Data 프록시가 Optional을 언랩해 SpEL `#result`가 `T`로 전달 → `!#result.isPresent()` SpEL 오류(7건). 분리 빈에서 `null`-반환 메서드로 래핑 + `unless="#result==null"` 으로 우회.
- **update 시 CacheEvict 제외**: `PortfolioService.updatePortfolio`는 `avg_buy_price`·`quantity`·`memo`만 수정, `stock_code` 불변 → `portfolioStockCodes` 캐시 내용 미변경 → evict 불필요.
- **DisclosureQueryService 목록 경로 무캐시**: `AnalysisResultRepository.findByDisclosureIdIn` (bulk) 은 캐시 미적용. 현재 단계에서 TTL 기반 전략보다 무캐시가 안전(실시간성 우선).
- **전역 staleTime 제거**: 각 훅이 명시적으로 staleTime 선언하지 않으면 TanStack 기본(0, always stale)으로 폴백 → 신규 훅 추가 시 명시 필수.

### 미완료 → 다음 세션
- `fe-accessibility-skeleton-ui` (Approved) 구현 대기
- `performance-caching-staletime` Spec → Done 전환(`/dc-spec-move performance-caching-staletime Done`)

---

## 2026-06-23 (29차) | E2E meCallCount 픽스 — AuthBroadcastListener 조건부 마운트

**산출**:
- FE: `app/test/concurrent-auth/page.tsx` — `{mode !== "concurrent" && <AuthBroadcastListener />}` 1줄 조건부 마운트
- Docs: `e2e-token-refresh-mecount-fix` Draft→Done, specs/README.md MOC 갱신

### 결정 (코드에 드러나지 않는 사항)
- **방안 B 채택**: 어설션 완화(A) 대신 픽스처 조건부 마운트(B) 선택. `(a)` 테스트는 Promise 큐 전용이라 BroadcastChannel 불필요. 다른 테스트(b·c)는 `mode` 파라미터 없이 방문 → `AuthBroadcastListener` 유지.
- **근본 원인**: `AuthBroadcastListener.useEffect`가 마운트 시 `fetchMe()` 1회 추가 호출 → 5×병렬 Promise 큐와 합산 시 meCallCount=11. 픽스처 페이지가 `(app)` 그룹 밖이라 layout.tsx에서 자동 마운트가 없어 직접 포함하고 있었음.

### 미완료 → 다음 세션
- `fe-accessibility-skeleton-ui` (Approved) 구현 대기
- Playwright keyboard-nav 테스트 실제 실행 검증 (dev 서버 필요)

---

## 2026-06-23 (28차) | 종목 검색 키보드 네비게이션 + WAI-ARIA + Playwright E2E

**산출**:
- FE: `portfolios/new/page.tsx` — `onKeyDown` 확장(ArrowDown/Up 순환·Enter·Escape), `activeIndex` state, `aria-activedescendant`, `role="option"` + `aria-selected` 동적 반영, `scrollIntoView`
- FE: `StockSearchCombobox.tsx` — 동일 키보드 패턴(sc-option prefix), `aria-haspopup="listbox"` 추가, value-based 디바운스로 단순화(`setDebounced` 제거)
- FE: `lib/hooks/useDebounce.ts` 공유 훅 신설 — 두 파일의 중복 로컬 `useDebounce` 통합
- FE: `e2e/portfolios/keyboard-nav.spec.ts` — Playwright (a)ArrowDown+Enter→라우팅, (b)순환 wrap, (c)Escape 3케이스
- Docs: `portfolio-search-keyboard-nav` Approved→Done, MOC 갱신

### 결정 (코드에 드러나지 않는 사항)
- **id prefix 분리**: `stock-option-${i}` (new/page) vs `sc-option-${i}` (StockSearchCombobox) — PortfolioSheet가 Combobox를 렌더하는 알림·온보딩 페이지와 new/page가 동일 viewport에서 공존할 경우 DOM id 충돌 방지. prefix 변경 시 반드시 양쪽 맞출 것.
- **`role="presentation"` 패턴**: "검색 중..." / "검색 결과 없음" li는 listbox 자식으로 선택 불가 → `role="presentation"` + 외부 `aria-live="polite"` sr-only 영역으로 이중 알림(시각+스크린리더). WAI-ARIA 1.2 listbox children 규칙 준수.
- **value-based debounce 전환**: Combobox의 imperative `setDebounced` 패턴은 `handleChange`에서 두 번 setState 했음 → value 기반으로 전환해 단일 `setQuery` 로 축약. 동작 동등하나 코드가 간결해짐.
- **atLimit + Enter 가드**: new/page.tsx에서 Enter 선택 시 `!atLimit` 체크 — 버튼 `disabled` 상태와 동기화. Free 3종목 한도 초과 시 키보드로도 추가 불가.

### 미완료 → 다음 세션
- Playwright keyboard-nav 테스트 실제 실행 검증 (dev 서버 필요, 현재 typecheck만 통과)
- `fe-accessibility-skeleton-ui` Spec (Approved) 구현 예정 (다음 우선순위)
- `e2e-token-refresh-mecount-fix` Draft Spec 구현 (1줄 fix — concurrent-auth fixture 조건부 mount)

---

## 2026-06-23 (27차) | E4 OAuth 약관게이트 + JWT claim + Vitest

**산출**:
- BE: `JwtTokenProvider.generateAccessToken()` 4번째 파라미터(`onboardingCompleted`) 추가, `CLAIM_ONBOARDING_COMPLETED` 상수, `extractOnboardingCompleted()` 헬퍼
- BE: `AuthService.issueTokenPairInternal()` — `onboardingCompletedAt != null` 여부로 claim 주입
- FE: `frontend/src/lib/auth/jwt-utils.ts` 순수 함수 `decodeJwtPayload()` 분리 (Edge Runtime 의존 없음, 테스트 가능)
- FE: `frontend/src/middleware.ts` 전면 재작성 — `PUBLIC_EXACT Set` + `ONBOARDING_PREFIXES` prefix 방식으로 `/signup/terms` 통과 버그 해소, E4 게이트 구현
- FE: `/signup/terms/oauth` 소셜 전용 약관 동의 페이지 신설 (`oauth/page.tsx`)
- FE: `TermsCheckboxList` 공유 컴포넌트 신설 — 이메일·소셜 양쪽 공유, WCAG 2.1 AA 완비
- FE: `/signup/terms/page.tsx` 단순화 — `isOAuth`/`Suspense`/`useSearchParams` 완전 제거
- FE: `/signup/complete/page.tsx` M-1 fix — 완료 후 `/api/auth/refresh` 호출로 새 토큰(claim 포함) 발급
- FE: `vitest.config.ts` + `jwt-utils.test.ts`(8건) + `middleware.test.ts`(22건) — Vitest 프로젝트 최초 단위 테스트 설정

### 결정 (코드에 드러나지 않는 사항)
- **H-1 backward-compat**: 기존 토큰엔 `onboarding_completed` claim이 없음 → `undefined !== true` 평가로 모든 기존 사용자 30분 중단 버그 발생 위험. `claims !== null && claims.onboarding_completed === false` 패턴으로 수정해 claim 없는 구 토큰은 게이트를 통과하게 처리.
- **D1 차단 기준**: `onboarding_completed` (V20 `onboarding_completed_at IS NOT NULL`)로 단일화. `consent_completed`(약관 3종만)보다 상위집합이라 더 엄격.
- **D2 redirect 목적지**: `/signup/terms/oauth` 단일 경로 (MVP). 향후 단계별 확장 가능.
- **PUBLIC_EXACT vs ONBOARDING_PREFIXES 분리**: `startsWith("/signup")` 로 `/signup/terms`를 통과시키면 `/signup/complete`도 통과 → `SIGNUP_PROTECTED_EXACT`로 세션 필수 예외 처리. `/signup/complete` 세션 없이 접근 → `/login?redirect=/signup/complete`.
- **Spec 전제 3건 정정**: ① `onboarding_completed` 대신 `consent_completed` 예정이었으나 V20에서 이미 `onboarding_completed_at` 필드로 확정됨 ② `/signup/terms?oauth=true` 대신 독립 경로로 설계 변경됨 ③ `hasRequiredConsents()`가 버그로 폐기되고 V20 필드 기반으로 전환됨.

### 미완료 → 다음 세션
- Playwright E2E 테스트에 E4 게이트 시나리오 추가 (OAuth 가입 후 약관 미동의 → redirect 흐름)
- `/dc-test-verify` 실행 — BE Testcontainers 전체 스위트 실행 확인 (현재 FE Vitest만 확인)
- Stage 3 RAG `similar_disclosures` Spec 별도 작성 필요

---

## 2026-06-23 (26차) | BE/DB API 정합 MVP R1 구현

**산출**:
- R1: `GET /notifications` 응답 `List<>` → `PageResponse<T>` 전환 (P0 수정 — FE 알림 파싱 복구)
- R2: `DisclosureListItemResponse.rceptDt` `BASIC_ISO_DATE` 포맷(YYYYMMDD) 수정 (P1 수정 — FE 날짜 그루핑 복구)
- R3: `AnalysisResponse` 주석 표준화 + `api_spec.md §2.4` Stage 3/5 null 정책 명시
- R4: `/signup` submit 버튼 `aria-label` 접근성 보강 (WCAG 2.1 AA)
- Testcontainers 통합 테스트 4건 추가: rcept_dt 포맷 2건 + 알림 PageResponse 구조 1건 + IDOR 격리 1건
- `/dc-tech-review` → `/dc-spec-move Approved` → `/dc-implement` → `/dc-review-code` → 이슈 수정 파이프라인 완주

### 결정 (코드에 드러나지 않는 사항)
- **sort 파라미터 고정**: FE가 `sort=` 보내도 BE에서 무시하고 `created_at DESC` 고정. sort injection 방어.
- **`findByUserId(Long)` no-arg 유지**: `NotificationDispatcherIntegrationTest` 13곳·`NotificationRetryJobIntegrationTest` 1곳이 테스트 assert용으로 직접 사용. 삭제 시도 → 컴파일 오류 확인. 주석을 "테스트 assert 및 소규모 유틸 조회용"으로 정정.
- **`PageImpl<>(dtos, pageable, page.getTotalElements())` 패턴**: `Page.map()` 쓰면 N+1 재발생 — `page.getContent()` 후 bulk-join → `PageImpl` 재조립이 올바른 패턴. 코드에 주석 명시.
- **FE 페이지 이동 UI 미구현**: `notifications.ts`가 `.content`만 소비하고 `page` 메타 미사용. MVP 범위 수용, 무한스크롤/페이저는 후속 FE Spec.

### 미완료 → 다음 세션
- `/dc-test-verify` 실행 — 통합 테스트 4건 포함 전체 스위트 실제 실행 확인
- FE 알림 무한스크롤/페이저 Spec 신규 작성 (현재 size=20 1페이지만 표시)
- Stage 3 RAG `similar_disclosures` Spec 별도 작성 필요

---

## 2026-06-22 (25차) | FE↔BE API 정합 분석 + Spec 수립

**산출**:
- `dc-review-frontend` 실행 — 4개 공개 페이지(home·login·signup·pricing) Playwright 캡처, 종합 **A등급**
- FE `lib/api/` 10개 파일 전수 분석 → 28개 REST 엔드포인트 계약 추출
- BE 컨트롤러 12개 + DTO + Migration V1~V20 현황 대조
- **P0 버그 식별**: `GET /notifications` — `List<>` 반환, FE + api_spec이 `PageResponse<T>` 요구 (알림 화면 파싱 불가)
- **P1 버그 식별**: `rcept_dt` 포맷 불일치 — BE "YYYY-MM-DD", FE `groupByDate()` "YYYYMMDD" 비교 → "오늘"/"어제" 그루핑 불가
- `docs/specs/Draft/be-api-alignment-mvp-r1.md` 생성 — 전수 비교표 + 구현 순서 포함
- `docs/specs/README.md` MOC 등록

### 결정 (코드에 드러나지 않는 사항)
- **DB 변경 없음 확정**: V1~V20 스키마가 MVP 전 기능을 커버. 알림 pagination 인덱스(`idx_notifications_user`) 이미 존재.
- **OTP 인메모리 유지**: Caffeine (단일 인스턴스 MVP) — 다중 인스턴스 전환 시 Redis 이관 후속.
- **similar_disclosures/financial_context = null**: 의도적 (Stage 3/5 미구현). FE 이미 null 처리 확인됨.
- **결제 Spec 제외**: payment-pg-integration.md 별도 Draft 존재, 본 세션 범위 아님.

### 미완료 → 다음 세션
- `be-api-alignment-mvp-r1` 구현 — `/dc-tech-review be-api-alignment-mvp-r1` → `/dc-implement` 순서
  - R1: `NotificationController` PageResponse 전환 (3파일)
  - R2: `DisclosureListItemResponse.rceptDt` BASIC_ISO_DATE 포맷 (1파일)
  - R4: `/signup` submit 버튼 aria-label (1파일)

---

## 2026-06-22 (24차) | 공시피드 2컬럼 레이아웃 재배치

**작업 내용**:
- **공시 피드 페이지 2컬럼 레이아웃 전환**: `grid grid-cols-1 lg:grid-cols-[3fr_2fr]` (6:4 비율)
  - 왼쪽(3fr): 공시 목록 — 날짜 그룹·카드·더 보기 버튼 전체
  - 오른쪽(2fr): Pro 전용 분석 패널 — `lg:sticky lg:top-20`으로 스크롤 시 고정
- **TierGate 조건 제거**: 기존 `{!isPro && <TierGate>}` → TierGate 자체가 Pro/비Pro 분기 처리하므로 무조건 렌더링으로 단순화
- **불필요 import 제거**: `useTierCheck` 훅 및 `isPro` 변수 삭제

---

## 2026-06-22 (23차) | 포트폴리오 라우트 재설계 + 대시보드 + 등록폼 완성 + dc-review-frontend

**작업 내용**:
- **라우트 구조 전면 재설계**:
  - `/portfolios` (기존: 검색 페이지) → **대시보드 페이지** (PortfoliosDashboardPage)
  - `/portfolios/new` (기존: 등록 폼) → **종목 검색 페이지** (PortfoliosNewPage, 이전 page.tsx 내용)
  - `/portfolios/add` (신규) → **종목 등록 상세 폼** (AddPortfolioPage, 2-column 레이아웃)
- **대시보드 (`/portfolios`)**: 총 평가금액·평가 손익(시세 연동 전 "—") + 보유 종목 수 + 이번 주 공시 4-stat카드, 보유 종목 테이블(평단·수량·현재가·수익률·평가금액·최근공시 badge), 우측 최근 공시 패널 + dark CTA 카드
- **등록 폼 (`/portfolios/add`)**: 2-column (보유 정보 + 알림 공시 종류), avg_buy_price `Controller` + `type="text"` + 쉼표 포매팅, 보유 수량 native spinner 숨김 + 커스텀 ▲▼ 버튼, 8개 프리셋 버튼(현재가에 덧셈 방식), AES-256 보안 안내
- **검색 페이지 (`/portfolios/new`)**: 이전 로직 이전 + "찾는 종목이 없나요?" 박스 추가, "알림 설정하기" CTA 제거, 종목 선택 후 `/portfolios/add?...` 이동
- **dc-review-frontend 실행**: Playwright API 모킹 + 6페이지 캡처, 종합 B등급
  - 즉시 수정 3건: 프리셋 `grid-cols-4 sm:grid-cols-8`, 대시보드 `grid-cols-2 lg:grid-cols-4`, 스피너 `py-0.5 → py-1.5`
- **이슈 문서화**: `docs/issues/portfolio-add-switch-overflow.md` (P3), `docs/issues/portfolio-csv-upload.md` (P2), HOME.md 이슈 섹션 신설

### 결정 (코드에 드러나지 않는 사항)
- **avg_buy_price `Controller` 사용**: `type="number"` input은 콤마 포매팅 불가 → `type="text"` + `inputMode="numeric"` + `Controller`로 원시 숫자 문자열 관리. `register()`로는 불가.
- **프리셋 버튼 "덧셈 방식"**: 금액 버튼을 누를 때 현재 입력값에 더하는 방식(SET 아님) — "168,000원에서 5만 올리기" 같은 점진적 조합 워크플로에 최적화.
- **네이티브 스피너 완전 숨김**: `min` + `step` 조합이 현재값 무시하고 배수로 스냅하는 브라우저 동작 → `[&::-webkit-inner-spin-button]:appearance-none` + 커스텀 버튼으로 대체.
- **Playwright auth 우회**: `dr_session` 쿠키는 middleware만 체크. API는 BE 8080 포트로 분리되어 있어 `page.route('**/localhost:8080/**')` 모킹으로 클라이언트 인증 루프(fetchMe→refresh→logout) 차단 가능.

### 미완료 → 다음 세션
- CSV 업로드 구현 (`docs/issues/portfolio-csv-upload.md`) — FE 파싱 + 확인 UI + BE bulk 엔드포인트
- Playwright review-capture.js overflow 임계값 15px 조정 (`docs/issues/portfolio-add-switch-overflow.md`)
- 포트폴리오 대시보드 시세 연동 (현재가·수익률·평가금액 "—" placeholder 상태)

---

## 2026-06-22 (22차) | 포트폴리오 검색 UX 재설계 + 접근성 수정 + Spec 2건

**작업 내용**:
- **portfolios 페이지 레이아웃 재설계**: 단일 패널(검색+목록+추천) → 2-패널 구조
  - 좌: 검색 카드 (input + "검색" 버튼 + 실시간 드롭다운 오버레이 + CSV 업로드 placeholder)
  - 우: 등록된 종목 카드 (X 버튼 삭제 + 카운트 배지 + "알림 설정하기 →" CTA)
- **StockSearchCombobox 제거**: 페이지 내부 인라인 combobox 구현 (useStockSearch 직접 사용)
- **접근성 수정 6건 (dc-review-frontend B등급 즉시 수정)**:
  - `useDebounce` 300ms 추가 — 매 키입력 API 호출 방지 (P1)
  - Escape 키 드롭다운 닫기, `aria-haspopup="listbox"` 추가
  - X 버튼 터치타겟 28px → 36px (WCAG 2.5.5)
  - Skeleton `role="status"` 래퍼 통합, `atLimit` placeholder 안내 문구
- **Spec 2건 생성**: `portfolio-search-keyboard-nav` (P1), `portfolio-csv-upload` (P3)

**미완료**:
- ArrowDown/ArrowUp/Enter 키보드 네비게이션 미구현 → `portfolio-search-keyboard-nav` Spec
- CSV 드래그앤드롭 핸들러 미구현 → `portfolio-csv-upload` Spec

---

## 2026-06-22 (21차) | TopBar 팝오버·설정 레이아웃 재구성 + 모바일 메뉴 + 버그 수정 9건

**작업 내용**:
- **TopBar 프로필 팝오버**: 프로필 아바타 클릭 → Link(/settings) 대신 Popover 드롭다운. navy 헤더(닉네임·이메일·플랜 배지) + PROFILE_MENU_ITEMS(마이페이지·알림설정·요금제·고객센터) + 로그아웃. `@base-ui/react/popover` — `asChild` 미지원으로 `PopoverTrigger` 직접 button 렌더
- **AppShell 사이드바 제거**: `Sidebar` import 제거, 웹 레이아웃 `md:flex` → `md:flex-col` (TopBar + main 수직 구조)
- **TopBar 글로벌 네비 통합**: 로고 + 대시보드/공시피드/포트폴리오 수평 링크 추가. `isActivePath()` 사용
- **마이페이지(/settings) 레이아웃 재구성**: 상단 2열(프로필|구독플랜) + 하단 2열(좌: 설정메뉴+로그아웃 / 우: 개인정보·보안+지원+앱버전). 개인정보·보안·지원 섹션 신규 추가
- **portfolios "+ 추가" 버튼 제거**: `/portfolios/new`가 `?code=` 미포함 시 즉시 리다이렉트하는 버그 있어 버튼 제거
- **PublicMobileMenu.tsx 신규**: `PublicNavbar`(RSC) 모바일 햄버거를 client 서브컴포넌트로 분리. Sheet 기반, `isAuthenticated` prop 연동
- `/dc-review-frontend` → B등급 4건 수정: PublicMobileMenu 신규, login aria-label, landing break-keep, AuthLayout items-center
- `/dc-review-code` 5-에이전트 → B등급, 즉시 수정 9건:
  - **TopBar**: Popover controlled open(pathname useEffect 닫힘) / aria-current = isActivePath 통일(WCAG) / initials 빈 문자열 `User` 아이콘 fallback / bell 미읽음 카운트 aria-label
  - **settings**: 닉네임 stale state useEffect 동기화 / handleSaveNickname catch+toast / alert()→toast.info() 3건 / tier_expires_at Invalid Date 검사 / setTimeout useRef cleanup / 취소 버튼 disabled={isSaving}
- `pnpm typecheck` 통과
- 이슈 문서: `docs/issues/topbar-settings-frontend-tech-debt.md` — 기술 부채 10항목(TIER_LABEL·NAV_ITEMS 중앙화, Sidebar 데드코드, Zustand 셀렉터, 디자인 토큰, AppShell 이중 마운트 등) P2/P3로 등록

**설계 결정**:
- `base-ui PopoverTrigger`의 `asChild` 미지원 확인 → `PopoverTrigger` 자체가 `<button>`으로 렌더되므로 래퍼 불필요. `asChild` 시도 시 TS 오류 발생.
- Popover 내부 Link 클릭 후 close 방법: `onOpenChange` + `useEffect([pathname])` 조합. Next.js App Router는 `router.events` 없으므로 pathname 감지가 유일한 경로.
- PublicNavbar RSC 유지 결정: `isAuthenticated`는 서버 httpOnly cookie 판정이므로 클라이언트에서 알 수 없음 → RSC 필수. 모바일 햄버거만 client 서브컴포넌트로 분리해 RSC 이점 보존.

**미완료 · 다음 세션**:
- 기술 부채 P2(TIER_LABEL·NAV_ITEMS 중앙화, Zustand 셀렉터, AppShell 이중 마운트, aria-controls) → `docs/issues/topbar-settings-frontend-tech-debt.md`
- `review-frontend-hover-capture` / `review-frontend-auth-capture` Spec 구현 대기
- M3(Cloud LLM 전환 / FE staleTime 튜닝 / BE Caffeine 캐시)

---

## 2026-06-22 (20차) | WCAG AA 접근성 + Skeleton UI + AlertDialog — M2 완료

**작업 내용**:
- `/dc-implement fe-accessibility-skeleton-ui` (W1~W3 전체)
  - **W1 접근성**: AppShell 스킵 네비(sr-only focus:not-sr-only) / TierGate role=region+aria-label / terms role=checkbox aria-checked(tri-state "mixed" 포함) / globals.css *:focus-visible 전역 폴백
  - **W2 Skeleton**: `skeleton.tsx` 신규 (animate-pulse + motion-reduce:animate-none) / `useDelayedLoading` hook(200ms delay) / portfolio·disclosures·disclosures[id]·notifications·dashboard 5페이지 로딩 텍스트→Skeleton
  - **W3 AlertDialog**: `alert-dialog.tsx` 신규 (@base-ui/react/dialog 재활용, 별도 dep 없음) / portfolios `window.confirm()` → AlertDialog 전환
- `/dc-review-code` 5-에이전트 리뷰(security·correctness·maintainability·performance·adversarial) → 종합 B+
  - **HIGH 3건** 즉시 수정: aria-checked="mixed" 누락(WCAG 4.1.2) / confirmDelete onError 에러 처리 / animate-pulse prefers-reduced-motion 미적용
  - **MEDIUM 3건** 즉시 수정: Skeleton.tsx→skeleton.tsx 소문자(Linux CI 호환) / Escape-while-isDeleting 다이얼로그 차단 / confirmDelete useCallback 추가
- `pnpm typecheck` 통과

**설계 결정**:
- AlertDialog는 `@radix-ui/react-alert-dialog` 미설치 → `@base-ui/react/dialog` 재활용 + `role="alertdialog"` override. 새 dep 추가 대신 기존 primitive 재사용 (통합기획서 §5 dep 최소화 원칙)
- useDelayedLoading 200ms 임계값: 네트워크 환경 상 `< 200ms` 응답은 skeleton 없이 즉시 콘텐츠 표시, `≥ 200ms`만 skeleton 노출 → CLS 최소화와 UX 사이 최적점
- skip nav 모바일 미동작(#main-content-mobile 별도 ID)은 허용 — 모바일은 탭바 구조로 사이드바 미존재, WCAG 2.4.1 모바일 적용 범위 외

**미완료 · 다음 세션**:
- `review-frontend-hover-capture` Draft → Approved → implement (Playwright timing race)
- `review-frontend-auth-capture` Draft → Approved → implement (BE 직접 호출 경로 수정)
- `portfolio-review-followup` Draft → tech-review → implement (M-1·M-4·M-5·M-6·L-1~L-5)
- M3 시작(6/23~): Cloud LLM 전환(`LLM_PROVIDER` env) / FE staleTime 튜닝(disclosures 5min·portfolios 30s) / BE Caffeine 캐시 TTL

---

## 2026-06-22 (19차) | PublicNavbar 인증 분기 + 네비 정리

**작업 내용**:
- `/dc-implement pricing-nav-auth-consistency` — `(public)/layout.tsx` `async` + `cookies()` presence 판정, `PublicNavbar`에 `isAuthenticated` prop 주입 / RSC 전환(`"use client"` 제거)
  - 로그인 사용자 → "대시보드로 →" 단일 CTA / 비로그인 → "로그인"(ghost) + "무료로 시작"(solid)
- `/dc-review-code` → Low: "로그인" `outline`→`ghost` 수정 (모바일 계층 정합)
- `/dc-review-frontend` 실행 → Info 2건(hover 캡처·auth 캡처) Spec 화 + `/dc-tech-review`
  - `docs/specs/Draft/review-frontend-hover-capture.md` — `data-pw-hover-idx` 패턴으로 timing race 픽스
  - `docs/specs/Draft/review-frontend-auth-capture.md` — BE 직접 호출 경로·browser 중복 생성 버그 보정
- 사이드바 `SETTING_ITEMS`(알림설정·요금제) + `알림` 메뉴 항목 제거 (nav 모달 중복 제거)
- `HamburgerDrawer` 알림설정·요금제 제거
- `TopBar` 미구현 검색 바 제거 → `docs/issues/topbar-global-search.md` 이슈 문서 작성
- `docs/issues/` 폴더 신설 + public-layout-dynamic-rendering-perf / public-navbar-aria-labels 이슈 문서 작성

**설계 결정**:
- `dr_session`은 httpOnly라 클라이언트 JS 읽기 불가 → 서버에서 `cookies()` presence-only 판정이 유일한 방법
  - `middleware.ts:28` 동일 기준 적용(유효성 검증은 BE 담당). 클라이언트 Zustand는 경쟁 조건 있음
- `(public)/layout.tsx`가 동적 렌더링이 되는 부작용은 현재 트래픽에서 무시 가능 수준 — MAU 임계치 도달 시 Suspense + useAuthStore 분리(C-plan → A-plan) 전환

**다음 세션**:
- `review-frontend-hover-capture` → `/dc-spec-move Approved` + `/dc-implement` (1 wave)
- `review-frontend-auth-capture` → `/dc-spec-move Approved` + `/dc-implement` (1 wave)
- TopBar 글로벌 검색 구현 시 → `/dc-plan TopBar 글로벌 검색`

---

## 2026-06-21 (18차) | portfolio-management-e2e 리뷰 수정 + 후속 Spec

**작업 내용**:
- `/dc-implement portfolio-management-e2e` — 전 Wave(BE W1·FE W2) 기구현 확인, FE Wave 2 dev-log 누락 항목 보충 기록
- `/dc-review-code` 4-에이전트 리뷰 (security·correctness·performance·maintainability): 종합 B+
  - **H-1** `PortfolioIntegrationTest` corp_name 검증 추가 — `createPortfolio` 단건·`listPortfolios` 2종목 bulk 경로 검증 (`containsExactlyInAnyOrder("삼성전자", "SK하이닉스")`)
  - **M-2** `portfolios/new/page.tsx` `avg_buy_price`·`quantity` 무조건 `Number()` → 조건부 변환 복원 (빈 값→0 전송 잠복 버그 차단)
  - **M-3** `portfolioSchemas.ts` `notify_enabled: z.boolean().default(true)` dead code 제거 (R3 옵션 A 이후 미정리)
- `docs/specs/Draft/portfolio-review-followup.md` 신규 — 미수정 7건(M-1·M-4·M-5·M-6·L-1·L-2·L-3·L-5) Spec화

**설계 결정**:
- `avg_buy_price` form 필드는 `required` 검증이 있어 현재 0 전송이 불가하나, 향후 검증 완화 시 즉시 잠복 버그가 현실화되므로 방어적 조건부 변환 복원
- L-4(Premium 페이지네이션)·L-6(종목 수정 edit 모드)는 별도 Spec으로 분리 — MVP 이후 우선순위

**다음 세션**:
- `portfolio-review-followup` → `/dc-tech-review` 후 구현 (Wave 1 BE: R1 오버로드 제거·R2 Caffeine 캐시·R3 NFE 방어·R4 merge 함수)
- `dashboard-real-data` Spec 구현 또는 `kakao-notification-channel` Spec 구현

---

## 2026-06-18 (17차) | code-review-fixes-onboarding-portfolio Spec Done 전환

**작업 내용**:
- `/dc-implement` 실행 → 전 Wave(W1~W4) 이미 커밋(203170d) 완료 확인
- `/dc-spec-move code-review-fixes-onboarding-portfolio Done` — frontmatter status/updated 갱신, `git mv` Approved→Done, README Approved 제거·Done 등재

**설계 결정**:
- L-3(UserService.completeOnboarding @Transactional 명시)는 dc-review-code 2차에서 "클래스 레벨로 충분" 판단으로 이미 제거된 상태 → 스펙과 리뷰 결정 충돌, 리뷰 결정 우선 적용

---

## 2026-06-18 (16차) | 랜딩 히어로 목업 구현 + dc-review-frontend 3건 수정

**작업 내용**:
- `page.tsx` — MOCK_DISCLOSURES(익명 A전자·B반도체·C바이오, 가상 티커) + HeroMockupCard 로컬 서브컴포넌트 + animate-in stagger 진입 애니메이션 + 면책 배지 + aria-hidden 컨테이너 구현
- `page.tsx` M-1: 카드 `bg-white/10→bg-white/[0.15]`, `border-white/10→border-white/20` (대비 5%p→10%p)
- `page.tsx` + `middleware.ts` L-1: `LandingRedirect` 제거 → middleware에서 `dr_session` 쿠키 체크 후 `/dashboard` SSR 리다이렉트. 비로그인 401+refresh 콘솔 에러 2건→0건
- `page.tsx` L-2: 면책 배지 `bg-white/5→bg-white/[0.08]`, `text-blue-300/70→text-blue-200`
- `LandingRedirect.tsx` 삭제 (데드코드)
- `docs/specs` Draft→Approved 이동 + README MOC 갱신

**설계 결정**:
- httpOnly 쿠키(`dr_session`)는 JS에서 읽기 불가 → Next.js middleware(서버)에서 읽어 SSR 리다이렉트로 전환. LandingRedirect의 클라이언트 fetchMe 전략 완전 제거
- 만료된 dr_session이 있어도 /dashboard 진입 → middleware가 /login으로 다시 리다이렉트 (이중 리다이렉트 허용, 정상 흐름)

---

## 2026-06-17 (15차) | dc-review-frontend Low 수정 + 랜딩 히어로 목업 스펙 작성

**작업 내용**:
- `signup/complete/page.tsx` — L-1: 체크 원 `bg-[color:var(--color-sentiment-positive)]`→`bg-primary` (온보딩 성공 아이콘을 주식 관례 빨강 대신 파란색으로). L-2: `md:items-center` 제거+`md:py-20` 추가 (PC에서 콘텐츠 수직 중앙 부유 해소)
- `docs/specs/Draft/landing-hero-mockup-enhancement.md` 신규 — 히어로 우측 placeholder를 시뮬레이션 공시 카드 목업으로 교체하는 스펙. 허구 데이터 면책 조항·진입 애니메이션·Tailwind 토큰 제약 포함
- `docs/specs/README.md` — 신규 스펙 Draft 섹션 등재

**설계 결정**:
- sentiment-positive(oklch 25°, 주황-적색)는 KR 주식 상승 관례색이지만 비금융 맥락(온보딩 완료)에 쓰면 경고/오류로 오인 가능 → `bg-primary`(파랑)로 범용 성공 의미 전달
- 랜딩 목업 스펙은 접근법 A(page.tsx 인라인) 권장 — LP 전용 비주얼이라 분리 오버헤드 불필요

**미완료**:
- `landing-hero-mockup-enhancement` 스펙은 Draft 상태 — `/dc-tech-review landing-hero-mockup-enhancement` 후 구현

---

## 2026-06-17 (14차) | 코드 리뷰 전수 수정 (code-review-fixes-onboarding-portfolio)

**작업 내용**:
- `V10→V20 rename`: Flyway V10 충돌(seed_stocks.sql과 동일 버전) → `git mv`로 V20으로 이동. 백필 조건 강화(TERMS AND PRIVACY 양쪽 동시 필요)
- `AuthService.java`: OAuth 재진입 경로에서 `deleteByUserId` 제거. 멀티디바이스 세션 전체 삭제 위험 → 미사용 토큰은 `deleteExpiredTokens()` 배치 위임. Caffeine JVM-단위 CSRF 캐시 경고 주석 추가
- `GlobalExceptionHandler.java`: `ex.getReason()` 클라이언트 노출 차단(400/401/403/404/429/5xx → generic). 409/410/422는 도메인 메시지로 사용 허용(주석 명확화). 410 `RESOURCE_GONE` code 케이스 추가
- `PortfolioRequest.java`: `@DecimalMax` (avgBuyPrice 999999999, quantity 100000000) — FE max validation과 정합
- `UserService.java`: 클래스 레벨 `@Transactional`과 중복된 메서드 레벨 제거
- `useSheetSide.ts`: `useState` lazy initializer 도입 — desktop 첫 렌더 flash 제거. `typeof window` SSR guard
- `PortfolioSheet.tsx`: `useId()`로 동적 id 생성(WCAG §4.1.1 중복 id 제거). RHF max validation 추가
- `signup/complete/page.tsx`: `useSheetSide` 훅 적용, `completeOnboarding onError` toast, `settingsError.body.status === 401` 타입 안전 401 탐지, `PortfolioSheet contentClassName` 추가(모바일 min-height 정합), eslint-disable 제거·deps 정규화
- `notifications/page.tsx`: `useSheetSide` 적용, `BOTTOM_SHEET_MIN_HEIGHT` 상수 사용, 온보딩 미완료 배너(포트폴리오·알림 미설정)
- `notifications.ts`: `staleTime: 60_000` 추가

**설계 결정**:
- 409/410/422 reason 클라이언트 노출: 이 코드들은 도메인 비즈니스 메시지 전달 용도이므로 노출 허용. 단, 호출자가 reason에 기술 정보(DB 제약명 등)를 담는 것은 코드 리뷰 컨벤션으로 금지
- useSheetSide lazy 초기화: `typeof window === "undefined"` guard + `window.matchMedia()` 즉시 호출로 첫 렌더부터 올바른 side 반환. Next.js "use client" 컴포넌트에서만 사용됨

---

## 2026-06-17 (13차) | OAuth 온보딩 전 절차 완료 강제 (onboarding_completed_at)

**작업 내용**:
- `V10__add_onboarding_completed_at.sql`: `users.onboarding_completed_at TIMESTAMPTZ` 추가. 기존 사용자(consent_logs 존재) → `created_at` 백필
- `UserEntity.java`: `onboardingCompletedAt` 필드 + `completeOnboarding()` 메서드(멱등)
- `UserService.java`: `completeOnboarding(Long userId)` 추가
- `UserController.java`: `POST /api/v1/users/me/onboarding-complete` 신규 (204, 멱등)
- `AuthService.java`: `oauthCallback` returning user 판단을 `hasRequiredConsents()` → `onboardingCompletedAt != null`으로 전환
- `auth.ts`: `useCompleteOnboarding()` 뮤테이션 추가
- `signup/complete/page.tsx`: 마운트 시 `completeOnboarding()` 호출

**설계 결정**:
- `is_new_user` 기준을 약관 동의(consent_logs)에서 온보딩 완료(/signup/complete 도달)로 전환. 약관 이후 phone/profile/complete를 건너뛰고 대시보드로 이동하는 경로를 차단
- 기존 사용자 백필: consent_logs 존재 = 온보딩 완료로 간주 → `onboarding_completed_at = created_at`. 기존 사용자 경험 유지
- 이메일 사용자도 `/signup/complete` 진입 시 마킹 (멱등) — 이메일 로그인에는 `is_new_user` 체크 없으므로 부작용 없음

---

## 2026-06-17 (12차) | 온보딩 종목 등록 409 버그 수정

**작업 내용**:
- `GlobalExceptionHandler.java`: `ResponseStatusException` 핸들러 추가. HTTP status → `code` 매핑(409→DUPLICATE_RESOURCE, 422→BUSINESS_RULE_VIOLATION 등) + ProblemDetail에 `code`·`message` 속성 명시
- `signup/complete/page.tsx`: `portfolioDone` 상태를 `useState(false)` → `usePortfolios().data?.length > 0` derived로 변경. 페이지 새로고침 후에도 DB 실제 상태 반영

**설계 결정**:
- Spring 기본 `ResponseStatusException` 처리는 RFC 7807 ProblemDetail에 `code`·`message` 필드를 포함하지 않음 → FE `ApiError` 인터페이스 계약({code, message}) 불일치가 근본 원인. `GlobalExceptionHandler`에서 직접 처리해 모든 `ResponseStatusException`에 일관된 계약 보장
- `portfolioDone`을 in-memory 상태로 관리하면 새로고침 시 초기화 → 이미 등록된 종목 재시도 가능. `usePortfolios()` 쿼리는 `staleTime: 60s`로 캐시되므로 추가 네트워크 비용 최소

---

## 2026-06-17 (11차) | 온보딩 a11y 버튼 라벨 + middleware 보호

**작업 내용**:
- `signup/complete/page.tsx`: "등록"·"설정"·"첫 종목 등록하기 →" 버튼 `aria-label` 추가 (`buttonsWithoutLabel` 3→0), 모바일 하단 여백 `pt-12` → `py-12` 대칭화
- `middleware.ts`: `PUBLIC_PATHS` prefix 매칭 → 온보딩 단계별 명시 열거. `/signup/complete` 보호 라우트로 전환 (미인증 → `/login` 리다이렉트)

**설계 결정**:
- `/signup/complete`는 세션 생성 이후 진입하므로 인증 필수. prefix catch-all(`/signup/`) 방식은 /complete까지 public으로 처리해 미인증 직접 접근이 가능했음 → 명시 열거로 수정
- 온보딩 단계(`/signup/verify`~`/signup/profile`)는 계정 생성 전이므로 public 유지

---

## 2026-06-17 (10차) | 온보딩 체크리스트 Sheet/Dialog 전환

**작업 내용**:
- `signup/complete/page.tsx`: "등록"·"설정" 링크를 `PortfolioSheet`(2-step)·`NotifDialog`(간소화)로 전환
- `PortfolioSheet`: StockSearchCombobox(Step1) → RHF 폼(Step2), POST /portfolios, Free422/중복409 에러처리, Sheet 닫힘 시 상태 초기화
- `NotifDialog`: 채널·빈도 설정, PUT /notifications/settings, `enabled:true` 강제, 설정 fetch 실패 에러 상태
- `portfolios/new/page.tsx`: 네트워크 오류 catch else 분기 추가 (공통 버그 수정)
- Sheet side 뷰포트 반응형 (sm 미만 bottom / sm 이상 right), mediaQuery 클린업

**설계 결정**:
- `enabled:true` 강제 포함 — 온보딩 저장 = 알림 활성화 의도 확정 (Tech Review 리스크#2)
- Sheet/Dialog 온보딩 1회성 UI → `page.tsx` 단일 파일 인라인 구현 (공유 컴포넌트화 불필요)
- `portfolioDone`·`notifDone` 리로드 시 초기화 허용 (온보딩 맥락 1회성)
- `NotifDialog` type_filter·off_hours_allowed는 서버 기존값 그대로 전송 (미노출 필드 변경 방지)

---

## 2026-06-16 (9차) | 종목 등록 진입 경로 수정

**작업 내용**:
- `signup/complete`: "등록"·"첫 종목 등록하기" 링크 `/portfolios/new` → `/portfolios`로 변경
- `portfolios/new`: `code` 파라미터 없이 접근 시 `/portfolios`로 redirect (useEffect)

**설계 결정**:
- 진입 경로: `signup/complete` → `/portfolios` (StockSearchCombobox) → 종목 선택 → `/portfolios/new?code=...&name=...`
- TopBar 장식용 search input(비기능)과 실제 StockSearchCombobox를 혼동하는 UX 문제 — 진입 경로 수정으로 우회

---

## 2026-06-16 (8차) | 종목 등록 필수 입력 + 검색 UX 개선

**작업 내용**:
- `portfolios/new/page.tsx`: avg_buy_price·quantity 선택→필수 변경 (required 유효성, `*` 표시, 에러 border, placeholder 구체화, min 1)
- `StockSearchCombobox.tsx`: `isSearching = (query !== debouncedQ) || isLoading` — debounce 300ms 동안 빈 드롭다운 대신 "검색 중..." 표시, `aria-live="polite"` 추가

**설계 결정**:
- BE `CreatePortfolioBody`는 `avg_buy_price?: number` optional 유지 (BE API 변경 없음) — FE 폼에서만 required 강제

**다음 세션**:
- `dashboard-real-data` Spec 구현

---

## 2026-06-16 (7차) | portfolio-management-e2e E2E 구현

**작업 내용**:
- Wave 1 (BE): `StockRepository.findByStockCodeIn()` bulk 추가, `PortfolioResponse.corpName` 필드 추가, `PortfolioService.listPortfolios()` N+1→bulk 리팩터
- Wave 2 (FE): `usePortfolios()` `staleTime: 60_000`, `portfolios/new/page.tsx` 알림 토글 제거 → `/notifications/settings` 안내 링크
- dc-review-code Medium 3건 수정: empty-check, `createPortfolio()` 이중 쿼리 통합(`existsByStockCode`→`findById`), 오해 유발 주석 교정

**설계 결정**:
- `findByStockCode()` 제거 → `findById(stockCode)` 사용 (Spring Data JPA PK 관용 패턴)
- `createPortfolio()` stocks 이중 쿼리 → `findById()` 단일 조회 후 존재확인+corpName 추출 통합

**다음 세션**:
- `dashboard-real-data` Spec 구현 (Draft → /dc-tech-review 후 구현)
- `kakao-notification-channel` Spec 구현

---

## 2026-06-16 (6차) | 스펙 감사 + 신규 Spec 3개 + Tech Review

**작업 내용**:
- 전체 스펙 현황 감사 (Done 18개, Approved 1개, Draft 7개)
- `auth-email-verify` Approved → Done 이동 (주석에 "구현 완료" 기록됐으나 폴더 미이동 상태였음)
- 신규 Spec 3개 작성 (dc-plan 스킬, 실제 코드 탐색 기반):
  - `portfolio-management-e2e` (Approved) — corp_name 추가·staleTime·알림 토글 제거
  - `dashboard-real-data` (Draft) — 오늘 날짜 필터·Free 5건 제한·Skeleton
  - `kakao-notification-channel` (Draft) — dev모드 fix·SMTP 설정·Wave3 카카오
- `portfolio-management-e2e` Tech Review 완료 → Approved 승인

**설계 결정**:
- per-stock `notify_enabled` → **옵션 A** 선택: 폼 토글 제거 + 알림 설정 링크 안내
- `corp_name` N+1 방지: `StockRepository.findByStockCodeIn()` bulk 조회 패턴
- 랜딩 페이지 히어로 placeholder → **W4 이후 스크린샷 교체** 유지

**다음 세션**:
- `/dc-implement portfolio-management-e2e` Wave 1(BE): StockRepository·PortfolioService·PortfolioResponse
- Wave 2(FE): usePortfolios staleTime·portfolios/new 알림 토글 제거
- 이후: `dashboard-real-data`, `kakao-notification-channel` 순서

---

## 2026-06-16 (5차) | 모바일 auth 페이지 반응형 heading + 로그인 문구 개선

**작업 내용**:
- `AuthLayout.tsx`: 모바일에서 좌측 aside 패널이 숨겨져 heading이 노출되지 않던 문제 수정
  - `main`에 `items-start md:items-center` 적용 (모바일 상단 정렬, 데스크톱 수직 중앙 유지)
  - `md:hidden` 브랜드 헤딩 블록 추가 (BrandMark + 태그라인 + heading + subtext)
  - 공유 컴포넌트 1곳 수정으로 6개 auth 페이지(signup·terms·verify·profile·phone·login) 자동 적용
- `login/page.tsx`: heading "다시 만나서 반가워요" → "공시레이더에 오신 걸 환영해요"
- `signup/complete/page.tsx`: AuthLayout 미사용 독자 레이아웃 `items-start md:items-center` 적용

**설계 결정**:
- 랜딩 페이지 히어로 우측 대시보드 placeholder는 **W4 완료 후 실제 스크린샷으로 교체** 예정 — 현재 유지
- `complete` 페이지는 AuthLayout을 쓰지 않는 독자 레이아웃으로 분리 유지 (성공 화면 특성)

---

## 2026-06-16 (4차) | OAuth signup/login 분기 검토 및 설계 결정

**작업 내용**:
- `/signup` 소셜 버튼 클릭 시 기존 계정이면 `/login?error=already_registered`로 분기하는 기능을 구현했다가 롤백
- `signup/page.tsx` 주석 1줄 업데이트 (콜백 동작 정확히 기술)
- dev-log 잘못된 항목 정정

**설계 결정**:
- **카카오 계정 1개 = DartCommons 계정 1개** (UNIQUE 제약). `/signup`과 `/login`에서 소셜 버튼의 동작은 동일 — 기존 계정이면 로그인, 신규면 약관 동의 플로우
- 카카오 다중 계정 전환은 **브라우저 쿠키 삭제** 후 재접근이 유일한 방법 (`prompt=login`으로 매번 선택 강제는 가능하지만 단일 계정 사용자 UX 저하 — 현재 방식 유지)
- `oauth_intent` 쿠키로 signup/login 진입 분기하는 방식은 "카카오 계정이 여러 개일 수 있다"는 전제가 잘못되어 폐기

---

## 2026-06-16 (3차) | OAuth 소셜 가입 약관동의 버그 수정 + 코드리뷰 후속 최적화

**작업 내용**:
- **버그 수정 (핵심)**: 카카오 회원가입 버튼 클릭 시 약관 동의 없이 바로 대시보드로 이동하는 버그 수정
  - BE `AuthService.oauthCallback()`: 신규 OAuth 사용자 즉시 가입+토큰 발급(autoSignup) → 계정만 생성(동의 보류) + `is_new_user=true` 반환으로 변경
  - BE `ConsentService.hasRequiredConsents()`: TERMS·PRIVACY·DISCLAIMER 동의 이력 확인 (기존 사용자도 동의 미완료 시 재유도)
  - FE `route.ts`: `is_new_user=true` 시 `/signup/terms?oauth=true` 리다이렉트 (기존 무조건 `/dashboard`)
  - FE `terms/page.tsx`: `isOAuth=true` 소셜 모드 분기 — `useOAuthConsent()` 훅으로 `POST /users/me/oauth-consent` 호출
  - BE `UserController.recordOAuthConsent()`: 신규 엔드포인트 + 멱등성(이미 동의 완료 시 204 즉시 반환)
- **P0 수정 (코드리뷰)**: `hasRequiredConsents()`에 DISCLAIMER 추가 (자본시장법 §11.1) + 동의 재진입 시 refresh_tokens 누적 방지(deleteByUserId 선행)
- **최적화**: `countAgreedRequiredConsents()` 전용 JPQL 쿼리 — 4타입 전체 로드 대신 3타입만 COUNT, `REQUIRED_CONSENT_TYPES` 상수 중앙화
- **Suspense 경계**: `terms/page.tsx` `TermsPageWrapper` 추가 (Next.js 15 `useSearchParams` 빌드 경고 해소)
- **Spec 문서**: `oauth-consent-enforcement.md` (middleware consent 강제화), `oauth-consent-data-integrity.md` (배치 정리+캐싱+agreed_at 불일치) — 향후 작업 계획

**설계 결정**:
- OAuth 자동 가입 시 `consent_logs` 미기록(동의 보류) → users 행은 생성되지만 동의 없는 상태. 이탈 시 "좀비 계정" 남을 수 있음 — `oauth-consent-data-integrity` Spec에서 배치 정리로 해소 예정.
- `?oauth=true` URL 파라미터 신뢰 방식은 단기 MVP 해법. 정식 해법은 `oauth-consent-enforcement` Spec의 JWT claims 인코딩 방식.
- `REQUIRED_CONSENT_TYPES` 상수를 `ConsentService`에 정의 — FE `TERMS_ITEMS`, `OAuthConsentRequest @AssertTrue`와 동기화 포인트.

**미완료 (Spec 기록)**:
- `oauth-consent-enforcement`: FE middleware consent 강제 체크 + JWT claims `consent_completed` 인코딩 + `OAuthTermsPage` 분리
- `oauth-consent-data-integrity`: V19 마이그레이션(agreed_at nullable) + Caffeine 캐시(TTL 1h) + 미완료 계정 배치 정리(@Scheduled)

---

## 2026-06-16 (2차) | 휴대폰 인증 UX 개선 + Kakao 개발 모드 + OAuth 버그

**작업 내용**:
- KakaoAlimtalkClient: `isDevMode()` 메서드 추가 + senderKey=placeholder 시 실제 API 호출 생략, BE 콘솔에 OTP 출력
- PhoneVerificationService: Kakao 발송 실패 시 rate limit counter 롤백 (EmailVerificationService 동일 패턴), 개발 모드 rate limit 전체 스킵
- phone/page.tsx: 번호 자동 포맷팅 `010-XXXX-XXXX`, 숫자만 입력/11자리 제한, OTP 만료 `expired` 상태 추가 — 타이머 00:00 도달·410 서버 응답 시 OTP 섹션 유지하며 "인증번호 다시 보내기" 버튼으로 전환, 만료 시 인증완료 버튼 비활성화
- profile/page.tsx: 초기 미선택 상태로 변경(기존 기본값 제거), 선택된 옵션 재클릭 시 해제 토글
- `callback/[provider]/route.ts`: `API_URL` 미설정 버그 → `NEXT_PUBLIC_API_URL` 사용 (API URL 이중 조립 방지)
- TopBar.tsx: OAuth 리다이렉트 후 `isLoading` 중 pulse 스켈레톤 표시 — "?"/사용자 null 플래시 제거

**설계 결정**:
- Kakao dev 모드 판별 기준: `KAKAO_SENDER_KEY=placeholder` (환경변수 기본값). 실 키 설정 즉시 운영 모드 전환, 코드 변경 불필요.
- OTP 만료 상태에서 OTP 섹션을 유지하는 이유: 번호 변경 후 재발송 편의. 섹션 사라짐 → 재입력 혼란 방지

**미완료**:
- 401 초기 요청 실패 근본 원인 미확정 — dr_session 쿠키가 BE로 전송되는지 Chrome Network 탭 확인 권장 (BE 로그 `[JWT] Invalid token:` WARN 없으면 쿠키 미전송). 현재 interceptor refresh 우회로 동작 중.

---

## 2026-06-16 | 온보딩 UI 개선 + BE 인증 강화

**작업 내용**:
- JwtAuthenticationFilter에 `dr_session` 쿠키 2순위 토큰 추출 지원 추가 (기존 Bearer 헤더 전용 → 쿠키 폴백)
- OAuth 이메일 미동의 사용자: 422 에러 대신 placeholder 이메일(`{provider}_{id}@oauth.placeholder`)로 자동 가입 처리
- EmailVerificationService: OTP 발송 실패 시 rate counter 복원 — 재시도 허용
- 약관동의 화면: "전체 동의합니다" → "필수 항목 전체 동의", toggleAll이 필수 항목만 토글하도록 수정
- 휴대폰 인증 화면: 번호 입력 4:1 비율, 인증요청 버튼 높이 동일화, 문구 수정
- `storeTokenCookies`에 응답 상태 검증 추가 — 쿠키 설정 실패 시 에러 명시
- 401 인터셉터: 공개 경로(`/signup/**` 등)에서 refresh 실패 시 로그인 리다이렉트 생략
- OAuth 카카오 도메인: `accounts.kakao.com` → `kauth.kakao.com` 수정
- AuthLayout 좌측 패널 비율 `1:1` → `1:2` 조정
- OTPInput 반응형 크기 개선 (모바일 h-12/w-11, sm: h-14/w-12)

**설계 결정**:
- AGE(만 14세 확인) 동의는 DB 미저장 결정 유지 — 사실 확인 선언이므로 법적 동의 이력 보관 불필요. 본인인증 도입 시 `consent_logs`에 `AGE_VERIFIED` 타입 추가로 대응 가능 (`docs/dev-log/design-decision-age-consent-ui-only.md` 참조)

**미완료**:
- phone/verify 첫 요청 401 근본 원인 미확정 — `dr_session` 쿠키가 BE로 전송되는지 Network 탭으로 확인 필요. BE 로그에서 `[JWT] Invalid token:` WARN 확인 권장

---

## 2026-06-11 | 디자인 명세서 정합성 수정 (dc-review-frontend)

**문제 진단**:
- CSP `connect-src` 에 경로 포함(`http://localhost:8080/api/v1`) → CSP 스펙상 경로 포함 시 정확한 URL만 허용, 하위 경로(`/users/me` 등) 전체 차단. 요금제 플랜 카드 미노출 + fetchMe 차단 원인
- Tailwind v4 `@theme inline`의 `--color-primary: var(--primary)` var() 체인 불안정 → 모든 Primary 버튼 검정으로 렌더

**수정 사항**:
- `next.config.ts`: `new URL(apiUrl).origin`으로 경로 제거 → `http://localhost:8080` origin만 사용
- `globals.css`: `--color-primary: var(--brand-blue)` 직접 참조로 고정
- `page.tsx`: 히어로 placeholder sentiment 배지, Feature 아이콘 44px/11px-radius/blue-bg
- `input.tsx`: 명세 기준 52px min-height, 1.5px border, 12px radius
- `docs/개발명세서/design/`: 파일 재구성 + design_structure.md 전면 업데이트

**결정**: CSP origin-only 방식 유지. 프로덕션에서도 `NEXT_PUBLIC_API_URL` origin 추출 자동 적용됨

---

## 2026-06-11 | mvp-missing-endpoints — Spec Done 전환

**산출**:
- `docs/specs/Approved/mvp-missing-endpoints.md` → `docs/specs/Done/` 이동 (git mv)
- frontmatter `status: Done`, `updated: 2026-06-11` 갱신
- 본문 상태 라인 → Done (phone verify·consent·pricing plans 구현 완료, 19 Testcontainers 통합 테스트 통과)
- `docs/specs/README.md` MOC: Draft 섹션 제거 → Done 최상단 추가

**확인 사항**:
- PhoneVerifyIntegrationTest: 8 tests, 0 failures
- ConsentIntegrationTest: 7 tests, 0 failures
- PricingIntegrationTest: 4 tests, 0 failures
- FE typecheck: 오류 없음

---

## 2026-06-11 | frontend-oauth-social + notification-read-status — Wave 1 BE + Wave 2 FE 완료

**산출**:
- **V18 마이그레이션**: `notifications.is_read BOOLEAN NOT NULL DEFAULT FALSE` + `read_at TIMESTAMPTZ` + `idx_notifications_unread` partial index
- **NotificationEntity**: `isRead`/`readAt` 필드 + `markRead()` 캡슐화 메서드
- **NotificationRepository**: `countByUserIdAndIsReadFalse` + `markAllReadByUserId` bulk JPQL UPDATE
- **NotificationResponse**: `is_read` 필드 추가 (FE 타입 1:1 대응)
- **NotificationHistoryService**: `markRead(userId, id)` IDOR 방어(userId 소유권 검증 → 403) + 중복 save 방지 + `markAllRead` bulk UPDATE + `getUnreadCount`
- **NotificationController**: `PATCH /{id}/read`, `PATCH /read-all`, `GET /unread-count` 3개 엔드포인트 추가
- **OAuth Route Handler**: `/api/auth/callback/[provider]/route.ts` 신규 — code+state 검증 → BE callback → httpOnly 쿠키 직접 설정(self-fetch 제거) → `/dashboard`
- **initiateOAuth**: `auth.ts`에 추가 — `getOAuthUrl` 도메인 검증 재사용 + 에러 시 toast 처리
- **signup/login 페이지**: `alert` placeholder → `initiateOAuth` + `.catch(toast.error)` 교체
- **login 페이지**: `?error=oauth_failed` 인라인 에러 표시 추가
- **notifications.ts**: `is_read` 타입 추가 + `useMarkAsRead`/`useMarkAllAsRead`/`useUnreadCount` 훅 3종
- **NotificationModal/NotificationsPage**: 로컬 Set → mutation 교체, `is_read` 서버 상태 기반
- **TopBar**: `useUnreadCount` 연결 — 미읽음 있을 때만 점 표시
- **NotificationReadIntegrationTest**: PATCH 읽음 처리·IDOR·전체읽음·unread-count 5건 Testcontainers 통합 테스트

### 결정 (코드에 드러나지 않는 사항)
- **OAuth BE 자동동의**: `AuthService.autoSignup()`이 소셜 신규 가입 시 약관 동의를 자동 처리 → FE callback에서 `/signup/terms` 분기 불필요(제거). 향후 명시적 동의 수집 필요 시 BE API 변경 필요.
- **OAuth self-fetch 제거**: 기존 `/api/auth/session` self-fetch 패턴 대신 callback route에서 직접 쿠키 설정 — edge runtime 호환성 및 안정성 향상.
- **unread-count 폴링**: staleTime 30초로 설정. WebSocket 도입 전까지 유지. 인증된 모든 페이지에서 TopBar가 폴링하므로 트래픽 영향 최소화.
- **카카오 개발자 콘솔**: Redirect URI 등록 필요 (운영자 작업) — `http://localhost:3000/api/auth/callback/kakao`, 프로덕션 도메인도 동일 패턴.

### 미완료 (다음 세션)
- 두 Spec(`frontend-oauth-social`, `notification-read-status`) Done 전환 필요 (`/dc-spec-move`)
- 카카오/구글 OAuth 실제 테스트는 개발자 콘솔 Redirect URI 등록 후 가능

---

## 2026-06-10 | mvp-missing-endpoints — Wave 1~3 + 리뷰 수정 전체 완료

**산출**:
- **V17 마이그레이션**: `users.phone_verified BOOLEAN NOT NULL DEFAULT FALSE` 추가 (phone_number_enc 등록과 OTP 인증 완료 분리)
- **PhoneVerificationService**: Caffeine 5분 TTL OTP 캐시 + 2단 rate limit(1분 1회 / 시간당 5회) + SecureRandom 6자리 + 시도 5회 초과 brute-force 차단(AtomicInteger attempts 카운터)
- **OTP 발송**: KakaoAlimtalkClient.sendOtp() — `otpMessageTemplate` yml 외부화로 카카오 비즈니스 콘솔 템플릿 변경 무코드 적용
- **UserController**: `POST /users/me/phone/verify`, `POST /users/me/phone/verify/confirm` 2개 엔드포인트 + UserMeResponse.phone_verified 노출
- **ConsentController**: `POST /consents`, `GET /consents/status` — 재동의 흐름 + requires_renewal 판단
- **ConsentService.getStatus()**: findLatestAllByUserId() 단일 쿼리로 N+1(4쿼리→1쿼리) 제거
- **consent_logs 감사 정확도**: recordReConsents()가 사용자가 제출한 실제 버전(termsVersion/privacyVersion)을 기록 (기존: 서버 CURRENT_POLICY_VERSION 고정)
- **PricingController**: GET /api/v1/pricing/plans — PricingProperties(@ConfigurationProperties) yml 바인딩, PUBLIC 엔드포인트
- **application.yml pricing.plans**: FREE(0원)/PRO(9,900원)/PREMIUM(29,900원) 3티어 외부화
- **FE**: useSendPhoneOtp/useConfirmPhoneOtp 훅, signup/phone TODO 2건 해소, lib/api/consent.ts, lib/api/pricing.ts, PricingClient 정적→useQuery 교체(staleTime 60s + skeleton + error state)

### 결정 (코드에 드러나지 않는 사항)
- **SMS 게이트웨이**: Aligo 불채택 → KakaoAlimtalkClient 재사용. OTP 템플릿(dc_otp_v1) 카카오 비즈니스 콘솔 등록 필요(운영자 작업, 코드 외).
- **phone_verified 명시 컬럼 채택**: `phoneNumberEnc != null`으로 파생하지 않음 — 번호 등록과 인증 완료를 독립 상태로 분리(재인증 플로우 대비).
- **PricingProperties vs system_configs**: 가격 변경 빈도 낮음(월~분기) + 멀티 인스턴스 동기화 불필요 → yml @ConfigurationProperties 채택. 빈번한 변경 필요 시 Admin API + DB 이관.
- **consent_logs 재동의 3종(TERMS/PRIVACY/MARKETING)**: 초기 가입 4종(+DISCLAIMER)과 다름. 재동의에서 DISCLAIMER는 버전 개념 없이 가입 시 1회만 수집.
- **C1 분리**: signup/verify/page.tsx 이메일 OTP는 별도 Spec(auth-email-verify Draft)으로 분리 — 본 Spec 범위 외.

### 미완료
- **Wave 4(통합 테스트)**: PhoneVerificationService/ConsentService/PricingController Testcontainers 통합 테스트 — 다음 세션 또는 dc-test-verify로 처리
- **auth-email-verify Spec**: Draft 상태. 이메일 OTP 흐름(POST /auth/email/send-otp + POST /auth/email/verify) 미구현 — signup/verify/page.tsx TODO 2건 잔존
- **카카오 OTP 템플릿 등록**: 운영자 카카오 비즈니스 콘솔에서 dc_otp_v1 템플릿 등록 필요 (배포 전 선행)

---

## 2026-06-10 | frontend-api-integration — R1·R9·R10 구현 완료

**Spec**: `docs/specs/Draft/frontend-api-integration.md`

**R1 (P0)** `.env.local`·`.env.example` `NEXT_PUBLIC_API_URL`에 `/api/v1` suffix 추가.
BE Controller가 `/api/v1/*`를 직접 매핑하는데 환경변수에 prefix 누락 → 환경변수 set 상태에서 모든 API 호출 404.

**R9** `AuthBroadcastListener`에 `fetchMe()` useEffect 추가 — `/dashboard` 등 직접 진입·새로고침 시 user null 복원.
`LandingRedirect`는 루트(`/`) 전용이라 `(app)` 그룹 직접 진입 커버 불가.

**R10** sonner 2.0.7 설치, `providers.tsx` `<Toaster />` 마운트, `useUpdateNotificationSettings`·`useTestNotification`·`useUpdatePortfolio`·`useDeletePortfolio`에 `onError toast.error` 연결.
`useCreatePortfolio`는 폼 에러(setError)로 처리 — onError 없음(중복 방지, 주석으로 문서화).

### 결정
- **fetchMe 위치**: `(app)/layout.tsx` 직접 전환 대신 기존 `AuthBroadcastListener`(이미 mounted, auth 전담)에 추가. 파일 신규 생성 없이 확장.
- **Toast import in API layer**: 아키텍처 결합 트레이드오프 수용 — `[사이드 임팩트]` 주석에 문서화. MVP 범위.
- **Zustand selector**: 리뷰 피드백 반영 — `useAuthStore()` → `useAuthStore(s => s.setUser)` 개별 selector로 교체.

### 미완료
- **frontend-api-integration Spec 상태**: 모든 R 완료했으나 Spec status 여전히 Draft. `/dc-spec-move frontend-api-integration Done` 처리 필요.
- **Double fetchMe**: `LandingRedirect`(루트) + `AuthBroadcastListener`(앱 그룹 최초 진입) — 로그인 플로우에서 최대 2회 `/users/me` 호출. MVP 허용, 추후 authStore.initialized 가드 추가 고려.

---

## 2026-06-10 | architecture-refactoring-cleanup — Spec Done 전환 (이미 구현 완료)

**Spec**: `docs/specs/Done/architecture-refactoring-cleanup.md`

모든 작업 카드(R1~R18, R10 skip)가 `7796f54`에서 이미 구현 완료됨을 확인.
테스트 120건 통과. Spec Approved → Done 전환 + README MOC 갱신.

### 결정
- **Approved Spec 3개 모두 Done 전환 완료** — be-api-blocking-bugs-fix, security-hardening-mvp, architecture-refactoring-cleanup 모두 구현 완료 상태 확인. Approved 폴더 비어있음
- **다음 우선순위**: Draft Spec 중 `frontend-api-integration`(FE-BE 실연동)이 MVP 데모에 직결 — 다음 세션 진입 권장

---

## 2026-06-10 | security-hardening-mvp — 보안 테스트 8건 + Spec 2개 Done

**Spec**: `docs/specs/Done/security-hardening-mvp.md`, `docs/specs/Done/be-api-blocking-bugs-fix.md`

**산출**:
- `SecurityHardeningIntegrationTest` 신규 — CORS preflight(허용·비허용), size=99999→400, size=100 경계, JWT 위변조→401, JWT 없음→401, Swagger/OpenAPI docs 401 (8케이스)
- `be-api-blocking-bugs-fix`, `security-hardening-mvp` Spec → Done 전환
- 전체 테스트 112 → 120건 (실패 0)

### 결정
- **SecurityHardeningIntegrationTest를 별도 파일로 분리** — CORS/JWT/size 시나리오는 DisclosureControllerTest(기능)·AdminAuthIntegrationTest(admin) 어느 쪽에도 맞지 않음. security 패키지에 전용 파일을 두어 보안 회귀 게이트를 명확히 분리
- **CORS 미허용 Origin 테스트는 `status()` 검증 없이 헤더 부재만 확인** — Spring Security가 미허용 CORS preflight를 403/200 중 어느 것으로 반환할지 구현에 따라 다름. `Access-Control-Allow-Origin` 헤더 부재만 검증하면 구현 변경에 강건

---

## 2026-06-10 | architecture-refactoring-cleanup — 아키텍처 정리 + 도메인 경계 복구

**Spec**: `docs/specs/Approved/architecture-refactoring-cleanup.md` (→ Done)

**산출** (Wave 1~4 + 리뷰 픽스):
- `shared/enums/Tier.java` — UserEntity.Tier·AnalysisResponse.Tier 이중 정의 통합. DB/JSON/JWT 와이어 포맷 무변경
- `shared/security/SecurityUtils.extractTier()` — DisclosureController·PortfolioController 중복 private 메서드 → 공통 유틸. AnonymousAuthenticationToken 가드 + `startsWith("ROLE_")` 앵커 수정
- `shared/ports/UserStockCodesPort` — disclosure·analysis 도메인의 `user.PortfolioRepository` 직접 의존 제거. 인터페이스를 shared/ports에, 구현체를 user/services에 배치해 import 방향 `analysis→shared`, `user→shared` 단방향 고정
- `disclosure/dto/DisclosureListItemResponse` — services/ → dto/ 패키지 이동 (CLAUDE.md §3-2)
- `analysis/dto/SimilarDisclosureItem` record — `List<Object>` → `List<SimilarDisclosureItem>` 타입 시그니처 확정 (Stage 3 구현 전 placeholder)
- `PortfolioRepository.findStockCodesByUserId` — 스칼라 프로젝션. avg_buy_price_enc 등 암호화 컬럼 로드 제거
- FE: `useTierCheck` 훅(isPro·isPremium 중복 4→1), `isActivePath` 유틸(Sidebar·BottomTabBar), `PortfolioListItem` memo 컴포넌트, `API_ERROR_CODES` const, `SUPPORT_EMAIL` 상수, `EXPECTED_REACTION_CONFIG` 맵, isSubmitting dead state 제거, RECOMMENDED_STOCKS 모듈 스코프
- 테스트 9건 신규: `AnalysisControllerTest`(6), `FeedbackServiceIntegrationTest`(3), `TierOrdinalInvariantTest`(1). 총 112/112
- 리뷰 즉시 픽스: DisclaimerNotice reportPath template literal 버그(HIGH), SecurityUtils AnonymousToken·ROLE_ 앵커(Medium), UserStockCodesPort 도메인 위치(Medium), IIFE→reactionCfg 변수(Medium), Record 타입 중복(Medium), Bell aria-hidden(Low), useTierCheck 런타임 가드(Low), stock_code @Pattern 검증(Low)

### 결정 (코드에 드러나지 않는 사항)
- **UserStockCodesPort를 shared/ports에 배치** — 인터페이스를 user/services에 두면 disclosure/analysis가 user 패키지를 직접 import해 CLAUDE.md §3-2 도메인 경계를 재위반. shared/ports가 anti-corruption layer 역할
- **Tier ordinal 불변식은 테스트로 기계 검증** — `SecurityUtils.extractTier`의 `max(ordinal)` 패턴이 enum 순서에 의존하므로, 순서 불변식을 TierOrdinalInvariantTest로 보호. 신규 Tier 추가 시 이 테스트가 가이드 역할
- **PortfolioListItem을 React.memo로 감싼 이유** — handleDelete가 `useCallback`으로 안정화됐으므로, 부모 재렌더 시 목록 아이템이 불필요하게 재렌더되지 않음. Free(3종목)/Pro(무제한) 모두 효과적

### 미완료
- R3 축소 범위: extractTier는 현재 DisclosureController·PortfolioController 2곳 적용. AnalysisController는 별도 미존재(피드백 엔드포인트는 DisclosureController 하위). 향후 신규 컨트롤러 추가 시 SecurityUtils 자동 재사용
- `isActivePath`의 Sidebar SETTING_ITEMS는 현재 `pathname.startsWith(href)` 인라인 유지 — `isActivePath` 미적용. 추후 Sidebar 전면 리팩 시 통합 권장

---

## 2026-06-10 | fe-correctness-investor-protection — Wave 1~4 전체 구현

**Spec**: `docs/specs/Approved/fe-correctness-investor-protection.md` (R1·R3~R7 구현 완료)

### 완료
- **R1 (sentiment 가드)**: `analysis ? (analysis.sentiment ?? disclosure.sentiment) : undefined` — analysis null 시 룰 기반 sentiment 노출 차단. "분析 대기 중" 배지(`role="status"`) 추가. 자본시장법 §11.1 준수
- **R3 (BE 페이지네이션 정합)**: `DisclosureRepository` native query 2종(`findFilteredByStocksWithSentiment`, `findAllFilteredWithSentiment`) 추가 — LEFT JOIN analysis_results. `DisclosureQueryService` 메모리 필터 제거. sentiment 지정 시 DB JOIN, null 시 JPQL fallback 유지
- **R4 (FE hasMore 가드)**: `disclosures/page.tsx` 페이지 누적(`filterRef` 패턴) + `canLoadMore = content.length >= PAGE_SIZE` 가드. "더 보기" 버튼 추가
- **R5 (email optional)**: `signup/terms` `useEffect` redirect + `email?.split` optional chaining
- **R6 (atLimit isLoading)**: `portfolios` `atLimit = isLoading || (!isPro && count >= FREE_LIMIT)`
- **R7 (analysis enabled)**: `useDisclosureAnalysis(id, { enabled: !!disclosure })` + 404 retry 차단
- **코드 리뷰 후속 수정**: pagination race condition(data+page 의존성), JPQL fallback(performance), canLoadMore 분리(버튼 UX)

### 결정
- **R2는 이미 구현됨**: `isWithheld` 로직 + 판단 보류 UI가 이전 wave에서 이미 존재 — skip
- **BE JPQL fallback 유지**: sentimentFilter=null(일반 피드 조회, 빈번한 path)은 LEFT JOIN 없는 JPQL 경로 유지. JOIN은 sentiment 지정 시에만 — 성능·dead code 두 문제 동시 해소
- **native query 선택**: Disclosure ↔ AnalysisResult JPA 매핑 없어 JPQL JOIN 불가 → `@Query(nativeQuery=true)` 사용. countQuery 별도 작성으로 Spring Data JPA pagination 정확도 확보
- **"분析 대기 중" 배지**: analysis 404(미완료)는 정상 상태로 처리. retry=false로 즉시 배지 표시(UX 개선)

### 다음 작업 (미완료)
- `fe-correctness-investor-protection` Spec → Done 전환 (`/dc-spec-move`)
- `architecture-refactoring-cleanup` — DTO 패키지, Tier enum, FE 중복 제거
- BE native query 테스트 추가 (코드 리뷰 Low 지적)

---

## 2026-06-10 | fe-auth-token-refresh-flow-rewrite R10 — Playwright E2E 테스트

**Spec**: `docs/specs/Approved/fe-auth-token-refresh-flow-rewrite.md` (R10 완료 → Spec 전체 완료)

### 완료
- **테스트 인프라 설치**: `@playwright/test 1.60.0` devDep 추가, `playwright.config.ts` 설정 (chromium, webServer 자동 기동, `test:e2e` 스크립트)
- **(a) Promise 큐 검증**: `e2e/auth/token-refresh.spec.ts` — 5개 동시 401 → refresh 1회 + meCallCount=10 정량 검증
- **(b) refresh 실패 redirect**: refresh 401 → LOGOUT_URL mock → `window.location.href='/login'` → `waitForURL('/login')` 검증
- **(c) BroadcastChannel 동기화**: `browser.newContext()` 동일 컨텍스트 두 페이지, pageA evaluate로 이벤트 발행 → pageB waitForURL 검증
- **(c-fallback) localStorage 폴백**: `addInitScript`로 BroadcastChannel 삭제 → storage event 경로 검증
- **테스트 픽스처 페이지**: `src/app/test/concurrent-auth/page.tsx` — `mode=concurrent` 쿼리로 5개 병렬 apiClient 호출, `AuthBroadcastListener` 직접 포함(app group 밖), prod guard

### 결정
- **픽스처 페이지 위치**: `(app)` 그룹 밖 → AppShell API 호출 간섭 제거. `AuthBroadcastListener`를 직접 포함해 BroadcastChannel 구독 보장
- **`mode=concurrent` 쿼리 파라미터**: 페이지 마운트 시 자동 API 호출을 옵트인으로 분리 — BroadcastChannel 전용 테스트(c/c-fallback)에서 의도치 않은 refresh 트리거 방지
- **순차 실행(`fullyParallel: false`)**: 인증 상태 쿠키 공유 방지, 테스트 간 간섭 없음
- **meCallCount=10 검증**: 초기 5건(401) + 재시도 5건(200) = 10. Promise 큐 동작의 정량적 증거

### 다음 작업 (미완료)
- `fe-correctness-investor-protection` — sentiment 노출 가드 (P1, 자본시장법)
- `architecture-refactoring-cleanup` — DTO 패키지, Tier enum, FE 중복 제거
- Spec `fe-auth-token-refresh-flow-rewrite` → Done 전환 (`/dc-spec-move`)

---

## 2026-06-10 | fe-auth-token-refresh-flow-rewrite — Promise 큐 + BroadcastChannel 다중 탭 동기화

**Spec**: `docs/specs/Approved/fe-auth-token-refresh-flow-rewrite.md` (R4~R9 완료)

### 완료
- **R4 (Promise 큐)**: `client.ts` `isRefreshing boolean` → `refreshPromise: Promise<void> | null` 패턴. 동시 401 요청 모두 동일 Promise를 await해 refresh 1회 보장, 이후 일괄 재시도
- **R5 (절대경로)**: SITE_ORIGIN 기반 절대경로 fetch — SSR/RSC 환경 상대경로 fetch 실패 방지
- **R6 (credentials)**: refresh fetch에 `credentials: "include"` 명시 (cross-origin httpOnly 쿠키 전송)
- **R7 (fallback)**: refresh 실패 시 logout Route Handler 호출 → `LOGIN_PATH` redirect. 재시도 1회 제한 유지. 5초 타임아웃으로 Promise 누수 방지
- **R8 (BroadcastChannel)**: `lib/auth/broadcast.ts` 신규 — iOS Safari polyfill(localStorage storage event 폴백) 포함. `authStore.logout()`에서 `broadcastAuth({ type: "logout" })` 호출. `AuthBroadcastListener` Client Component → `(app)/layout.tsx` 마운트
- **R9 (server-client)**: `lib/api/server-client.ts` 신규 — `next/headers cookies()` 기반 Server Component 전용 클라이언트. `dr_session` 쿠키 → Authorization 헤더 변환
- **constants.ts**: 분산된 경로 리터럴(LOGIN_PATH/SESSION_PATH/REFRESH_PATH/LOGOUT_PATH/SITE_ORIGIN) 단일 소스로 정리
- **Spec Draft → Approved**: `fe-auth-token-refresh-flow-rewrite.md` git mv 완료

### 결정
- **AuthBroadcastListener 위치**: `(app)/layout.tsx`는 Server Component이므로 Client Component를 별도 파일로 분리해 마운트. `setUser(null)` 후 `router.push(LOGIN_PATH)` — authStore.logout()의 full logout(Route Handler 호출)과 달리 수신 측은 쿠키가 이미 삭제된 상태이므로 상태만 초기화
- **R1~R3 (BE Set-Cookie 연동)**: BE가 직접 Set-Cookie를 발급하는 흐름은 별도 BE Spec에서 처리. 현재 `storeTokenCookies()` (Session Route Handler 경유) 방식 유지 — 백워드 호환
- **SITE_ORIGIN SSR 폴백**: `typeof window !== "undefined"` 분기 → `NEXT_PUBLIC_SITE_URL` env → `localhost:3000`. client.ts는 클라이언트 전용이지만 안전 장치로 절대경로 사용

### 다음 작업 (미완료)
- **R10 (Playwright 테스트)**: 동시 401 5건 → refresh 1회 검증 / refresh 실패 → /login redirect / 다중 탭 BroadcastChannel 동기화 테스트
- `fe-correctness-investor-protection` — sentiment 노출 가드 (P1, 자본시장법)
- `architecture-refactoring-cleanup` — DTO 패키지 정리, Tier enum, FE 중복 제거
- FE 미커밋 변경사항 — `portfolios/`, `disclosures/`, `auth/` 페이지 및 Route Handler

---

## 2026-06-10 | security-hardening-mvp — 보안 강화 전체 Wave (+ FE-BE정합 미커밋 복원)

**Spec**: `docs/specs/Approved/security-hardening-mvp.md` (Wave 1+2+3 완료)

### 완료
- **R1 (IDOR)**: `DisclosureController.analysis()`에 `hasPortfolioAccess()` 소유권 검증 + `list()`에 tier 추가
- **R2 (Feedback IDOR)**: `FeedbackService.upsert()`에 `analysisId→disclosureId→stockCode→portfolio` 체인 검증
- **R3 (rate-limit)**: Caffeine 30건/시간 rate-limit (userId 단위, 인스턴스 로컬)
- **R4 (scope=all)**: Free 티어 scope=all 403 차단 (Pro+ 전용 BM 정책)
- **R5 (CORS)**: `SecurityConfig.CorsConfigurationSource` 빈 + ALLOWED_ORIGINS 환경변수
- **R6 (CSP)**: `next.config.ts` CSP 헤더 (connect-src API_URL, frame-ancestors none 등)
- **R7 (Swagger)**: admin chain으로 이관 + application.yml 기본 비활성화
- **R8 (OAuth)**: `auth.ts getOAuthUrl()` 도메인 화이트리스트 (kakao/google/naver)
- **R9 (size cap)**: `@Max(100)` + Math.min 이중 방어
- **R11 (JWT log)**: `JwtAuthenticationFilter` WARN 로그 (token 원본 미포함)
- **R12 (reason)**: V16 마이그레이션 + `FeedbackEntity.update()` 2000자 가드
- **R13 (TOCTOU)**: `tryInsertFeedback(REQUIRES_NEW)` 격리 — 충돌 트랜잭션만 롤백
- **R14 (Base64)**: `JwtTokenProvider` Base64 디코딩 + `JwtProperties @Size(min=44)` + 테스트 secret 갱신
- **리뷰 게이트 수정**: TOCTOU catch(PostgreSQL aborted tx) → REQUIRES_NEW로 수정, CORS origins trim 추가

### 결정
- **R1 disclosure detail 공개 정책**: `GET /disclosures/{id}` = DART 공개 데이터이므로 인증만 요구, 소유권 검증 없음. 분석 결과만 소유권 필요.
- **scope=all BM**: Free = portfolio 3종목 한정. stockCode 단일 파라미터는 Pro+ 제한 적용 안 함(BM 차별화 대상 아님).
- **FeedbackService rate-limit 인스턴스 로컬**: MVP 단계에서 Redis 불필요. 수평 확장 시 Redis로 교체.
- **R14 파괴적 변경**: 기존 `JWT_SECRET` raw string → Base64 재생성 필요. `openssl rand -base64 32`
- **FE-BE정합성 미커밋 소스**: AnalysisController, FeedbackService 등 FE-BE정합성수정 미커밋 BE 소스를 이 커밋에 포함 (be-api-blocking-bugs-fix 커밋 후 repo 컴파일 불가 상태 해소).

### 다음 작업 (미완료)
- `fe-auth-token-refresh-flow-rewrite` — Promise 큐 + httpOnly 쿠키 (P0)
- `fe-correctness-investor-protection` — sentiment 노출 가드 (P1, 자본시장법)
- `architecture-refactoring-cleanup` — DTO 패키지, Tier enum, FE 중복
- FE 미커밋 변경사항 — `portfolios/`, `disclosures/`, `auth/` 페이지 및 Route Handler

---

## 2026-06-10 | be-api-blocking-bugs-fix — BE P0 6건 일괄 픽스

**Spec**: `docs/specs/Approved/be-api-blocking-bugs-fix.md` (단일 Wave 완료)

### 완료
- **R1·R2**: `DisclosureRepository.findFiltered` → `findFilteredByStocks`/`findAllFiltered` 분리. Hibernate 6 Collection IS NULL 미지원 버그 해소. JPQL ORDER BY 명시, Pageable Sort 이중 적용 제거
- **R3**: `DisclosureQueryService.list()` ids.isEmpty() 가드 추가 — Hibernate IN() SQL 오류 방지
- **R4**: `PortfolioRequest.avgBuyPrice`/`quantity` `@NotNull` 제거 + `PortfolioService` null 암호화 분기 — FE optional 필드 수신
- **R5**: `AnalysisResponse.from()` `premium ? null : null` dead code → `null // TODO Stage-5`
- **R6**: `DisclosureController.extractTier()` `findFirst()` → `max(ordinal)` — PREMIUM > PRO > FREE 최고 티어 보장
- **테스트 21건 추가**: DisclosureControllerTest(7) + AnalysisResponseTest(5) + PortfolioRequestValidationTest(9), 100/100 통과
- **pre-existing 픽스**: `AuthIntegrationTest`/`PortfolioIntegrationTest` camelCase → snake_case JSON 정합

### 결정
- **extractTier 위치**: security-hardening-mvp Spec에서 `SecurityUtils.extractTier()`로 이관 예정 — 지금은 로직만 수정
- **financial_context**: Stage 5 미구현 상태에서 모든 티어 null — `TODO Stage-5` 주석으로 수정 포인트 표시
- **ids.isEmpty() 패턴**: Hibernate의 IN() 빈 배열 처리를 애플리케이션 레벨에서 차단 (DB dialect 의존 방지)

### 다음 작업 (미완료)
- `security-hardening-mvp` — IDOR·CORS·CSP·Swagger·JWT감사로그 (P0 4건)
- `fe-auth-token-refresh-flow-rewrite` — race condition·httpOnly 쿠키·BroadcastChannel (P0 3건)
- `fe-correctness-investor-protection` — sentiment 노출 가드·페이지네이션 정합 (P1)

---

## 2026-06-09 | FE W7 — 결제·계정·공유 (T26~T29) ★ 전체 완료

**Spec**: `docs/specs/Approved/frontend-full-ui-implementation.md` (전체 29카드 완료 → Done 전환)

### 완료
- **T26 ProUpsellModal**: (app)/layout.tsx 등록 완료 (W1에서 구현)
- **T27 결제 UI mockup**: `/checkout`(7일체험·등록카드·카카오페이분기) + `/checkout/new`(신규카드·PCI-DSS고지)
- **T28 마이페이지(D16/m24)**: 프로필 닉네임 인라인 수정 + 플랜 표시 + 설정 메뉴 + 로그아웃
- **T29 공유 카드(D22/m21)**: CSS 카드 미리보기 + navigator.share API + 클립보드 폴백

### 결정
- **실결제 미연동**: 카카오페이/토스페이먼츠 SDK는 별도 Spec에서 연동. 카드번호 PCI-DSS 고지 추가
- **이미지 저장**: html2canvas 또는 Server-Side OG 이미지 생성으로 교체 예정(현재 alert 안내)
- **share-summary API 미존재**: authStore + portfolios 로컬 집계로 대체

### 7 Wave 완료 요약 (2026-06-09 하루 세션)
- W1: 레이아웃·공통컴포넌트 기반 (37개 파일)
- W2: 랜딩·요금제 (4개 파일)
- W3: 온보딩 4단계 전체 (13개 파일)
- W4: 공시 피드·공시 상세 (Free·Pro·Premium·피드백)
- W5: 종목 관리 (등록·검색·상세)
- W6: 알림 (설정·센터·모달)
- W7: 결제·계정·공유

---

## 2026-06-09 | FE W6 — 알림 설정·센터·모달 (T23~T25)

**Spec**: `docs/specs/Approved/frontend-full-ui-implementation.md` (W6 완료)

### 완료
- **T23 알림 설정(D14/m06)**: 채널 라디오(KAKAO·EMAIL·TELEGRAM) + 빈도 칩 + 유형 필터 + 야간 토글 + PUT /notifications/settings. 알림톡 미리보기(D25/m08) 토글로 표시
- **T24 알림 모달(D23/m25)**: TopBar 벨 클릭 팝오버. 최근 4건 + 안읽음 점 + 모두읽음. 읽음 처리 로컬 Set 임시 구현
- **T25 알림 센터(D24/m26)**: 전체 알림 날짜 그룹 + 필터(전체·안읽음·호재·악재) + 로컬 읽음 처리

### 결정
- **읽음 처리 클라이언트 임시**: 백엔드 `is_read` 컬럼 미존재(Tech Review 기록). Zustand Set으로 임시 처리. 백엔드 PATCH /notifications/{id}/read 추가 시 교체

---

## 2026-06-09 | FE W5 — 종목 관리 (T21·T22)

**Spec**: `docs/specs/Approved/frontend-full-ui-implementation.md` (W5 완료)

### 완료
- **T21 종목 관리(D12/m05·D12b/m05b)**: StockSearchCombobox 자동완성, Free 쿼터 바(3/3 초과 시 검색 비활성+Pro 유도), 추천 종목 카드, 등록 종목 목록+삭제
- **T22 종목 등록 상세(D13/m22)**: 매수 평균가·수량 입력(선택), 알림 토글 switch, 422(Free초과)·409(중복) 에러 인라인 처리

### 결정
- **매수가·수량 console.log 금지**: 금융 개인정보. 오류 시 ApiException 메시지만 표시, 입력값 로그 없음
- **edit 모드(PATCH) 미구현**: 기존 종목 수정은 W7에서 추가. 현재 수정 버튼은 new?edit={id}로 이동 준비만

---

## 2026-06-09 | FE W4 — 공시 피드·공시 상세 (T17~T20)

**Spec**: `docs/specs/Approved/frontend-full-ui-implementation.md` (W4 완료)

### 완료
- **T18 공시 피드(D15/m23)**: 감성 필터 칩(전체·호재·악재·중립) + 날짜 그룹(오늘·어제) + Pro TierGate(3개월 이력)
- **T19 공시 상세 Free+Pro(D3/m04)**: 헤더(DART 원문 인용 그대로) + SentimentBadge + ConfidenceMeter + 3줄 요약 + 예상 방향 + PriceReactionChart TierGate
- **T20 공시 상세 Premium(D17/m17) + 피드백(D18/m20)**: Premium TierGate + FeedbackPrompt + DisclaimerNotice 웹/모바일 모두 상시 노출

### 결정
- **면책 고지 이중 렌더**: 웹은 사이드바에, 모바일은 페이지 하단에 `DisclaimerNotice` 각각 배치(lg:hidden 분기). 모든 경우 노출 보장
- **Premium financial_context**: 현재 JSON 원시 출력. W7에서 구조화 테이블로 교체 예정

### 미완료 (다음 Wave)
- **W5** 종목 관리 (등록·검색·상세)
- **W6** 알림 설정·센터·모달

---

## 2026-06-09 | FE W3 — 온보딩 플로우 전체 (T11~T16)

**Spec**: `docs/specs/Approved/frontend-full-ui-implementation.md` (W3 완료)

### 완료
- **T11 로그인·가입(D5/m02)**: 카카오·구글 소셜 placeholder + 이메일/비밀번호 폼, AuthLayout 스플릿, 자본시장법 고지 상시 노출
- **T12 이메일 인증(D6/m10)**: OTPInput 6칸 + 5분 타이머 + 재전송 UI
- **T13 약관 동의(D7/m11)**: 전체/필수4/선택1 체크박스, AGE 로컬 처리, POST /auth/signup 호출
- **T14 휴대폰 인증(D8/m12)**: 번호 입력 + SMS OTP + 카카오 채널 안내 + "나중에" 스킵
- **T15 프로필 입력(D9/m13)**: 투자 경험 라디오 + 주 사용 시점 세그먼트, PATCH /users/me
- **T16 가입 완료(D10/m14) + 빈 대시보드(D11/m15)**: 환영 메시지·체크리스트, 대시보드 empty state + 추천 종목 + 알림 설정 유도

### 결정
- **Zod v4 + @hookform/resolvers**: 타입 충돌 발생 → `zodResolver(schema as any)` 캐스팅. 런타임 정상 동작 확인. 추후 @hookform/resolvers 버전 업으로 해소 예정
- **AGE 동의**: API ConsentType에 없으므로 로컬 체크박스만 처리(필수 완료 게이트). API 전송 제외

### 미완료 (다음 Wave)
- **W4** 대시보드 실데이터·공시 피드·공시 상세 구현 (현재 대시보드는 구조+empty state까지)

---

## 2026-06-09 | FE W2 — 랜딩·요금제 페이지 (T9·T10)

**Spec**: `docs/specs/Approved/frontend-full-ui-implementation.md` (W2 완료)

### 완료
- **T9 랜딩 (D1/m01)**: 히어로(네이비+카피+CTA) + 기능 4종 그리드 + CTA 배너 + 면책 고지 footer. LandingRedirect(로그인 세션 시 /dashboard 이동) 클라이언트 컴포넌트 분리
- **T10 요금제 (D4/m07)**: PlanCard 3종(Free·Pro·Premium) + 현재 등급 바인딩(PricingClient) + B2B 섹션 + 면책 고지

### 결정
- **PricingClient 분리**: authStore(클라이언트 전용)를 page.tsx(서버 컴포넌트)에서 쓸 수 없어 별도 클라이언트 컴포넌트로 분리. metadata export 유지
- **플랜 데이터 정적**: MVP는 하드코딩. W7에서 `/pricing/plans` API 연동 시 TanStack Query로 교체

### 미완료 (다음 Wave)
- **W3** 온보딩 4단계 — 로그인·가입·이메일인증·약관·휴대폰·프로필·완료

---

## 2026-06-09 | FE W1 — 레이아웃 셸 + 공통 컴포넌트 기반 구축

**Spec**: `docs/specs/Approved/frontend-full-ui-implementation.md` (W1 완료)

### 완료
- **T1** Pretendard(가변폰트, next/font/local) + IBM Plex Mono + globals.css 브랜드 토큰(navy·blue·sky·primary 오버라이드)
- **T2** Route Groups `(public)/(auth)/(app)` + `middleware.ts` (dr_session httpOnly 쿠키 가드)
- **T3** shadcn CLI 9종 UI 컴포넌트 추가 (input·badge·card·dialog·sheet·tabs·switch·progress·popover)
- **T4** 레이아웃 9종: AppShell(웹/모바일 분기 md:)·Sidebar·TopBar·BottomTabBar·HamburgerDrawer·MobileAppBar·AuthLayout·PublicNavbar·BrandMark
- **T5** 도메인 기반 4종: SentimentBadge(색+아이콘+텍스트 3중)·ConfidenceMeter·DisclaimerNotice·TierGate
- **T6** 도메인 심화 3종: DisclosureCard(Progressive Disclosure)·OTPInput(자동포커스·붙여넣기)·FeedbackPrompt
- **T7** 도메인 복합 4종: StockSearchCombobox·PlanCard·ProUpsellModal·PriceReactionChart(Recharts)
- **T8** API 레이어: lib/api/5종(client·auth·disclosures·portfolios·notifications·stocks)·lib/stores/2종(auth·ui)·lib/schemas/3종

### 결정 (코드에 드러나지 않는 사항)
- **인증 전략**: NextAuth v5(beta) 대신 Zustand authStore + httpOnly cookie(dr_session). 이유: beta 안정성 우려, 백엔드 자체 JWT와 직접 연동이 더 단순
- **asChild 미사용**: @base-ui/react Button이 `asChild` 미지원 → `buttonVariants()` 클래스를 Link에 직접 적용
- **알림 읽음 처리**: 백엔드 `is_read` 컬럼 미존재 → W6에서 Zustand 로컬 상태 임시 처리 후 백엔드 추가 시 교체

### 미완료 (다음 Wave)
- **W2** 랜딩(D1/m01) + 요금제(D4/m07) 실제 페이지 구현
- **W3** 온보딩 4단계 (가입·이메일인증·약관·휴대폰·프로필·완료)
- **W4** 대시보드·공시 피드·공시 상세 (핵심 앱 화면)

---

## 2026-06-08 | M2 user-auth Wave 5 — 통합 테스트 + 기존 테스트 수정

**Spec**: `docs/specs/Approved/user-auth-jwt-oauth2.md` (Wave 5 완료)

### 완료
- `AuthIntegrationTest` (6 테스트): signup 201, 중복 409, login 200, 잘못된 비밀번호 401, refresh rotation + 기존 토큰 401, logout 204 + 토큰 무효화
- `PortfolioIntegrationTest` (8 테스트): CRUD 201/200/204, Free 3종목 초과 422, IDOR GET/DELETE 403, AES-256-GCM DB 바이트 직접 검증
- 기존 테스트 31건 수정: `dartcommons.admin.password=test-admin-password` + `dartcommons.llm.provider=mock` 누락 해소 (8개 테스트 클래스)
- `src/test/resources/application.yml`에 admin/llm/dart/krx 공통 기본값 추가 → 신규 테스트 자동 정상화
- 전체 58/58 테스트 통과 (Testcontainers PostgreSQL)

### 결정 (코드에 드러나지 않는 사항)
- **test yml 기본값 전략**: `dartcommons.admin.password`, `dartcommons.llm.provider=mock`을 `@TestPropertySource`마다 반복하는 대신 `src/test/resources/application.yml`에 한 번만 정의. `@TestPropertySource`로 덮어쓰는 테스트는 override 방식 유지.
- **MockLlmClient 활성 조건**: `dartcommons.llm.provider=mock` 명시 필수(`matchIfMissing=false`). test yml의 `${LLM_PROVIDER:mock}` fallback은 Spring 환경 우선순위 상 무시됨 — 명시 프로퍼티가 필수.
- **Wave 5 종료**: Spec `user-auth-jwt-oauth2` → `/dc-spec-move user-auth-jwt-oauth2 Done` 필요

### 미완료
- `/dc-spec-move user-auth-jwt-oauth2 Done` — Spec 상태 Approved → Done 전환

---

## 2026-06-08 | sentiment-to-shared — Sentiment enum shared/enums 이관

**Spec**: `docs/specs/Approved/sentiment-to-shared.md` (단일 Wave 완료)

### 완료
- `shared/enums/Sentiment.java` 신규 생성 (POSITIVE/NEUTRAL/NEGATIVE)
- `AnalysisResult.java` 중첩 enum 제거, `sentiment` 필드 타입 → `shared.enums.Sentiment` import
- 본문 7개 + 테스트 5개 import 교체 (`AnalysisResult.Sentiment` → `shared.enums.Sentiment`)
- 79/79 통과, `grep -r "AnalysisResult.Sentiment"` 잔존 0건 확인
- notification-dispatcher·notification-retry-job Spec Done 전환 + `user-auth-jwt-oauth2` 이중 확인(이미 Done)

### 결정 (코드에 드러나지 않는 사항)
- **Sentiment 외 이관 후보**: `AnalysisResult.ExpectedReaction`도 동일 패턴의 cross-domain 후보이나 현재 notification/infra에서 참조 없어 이번 Spec 범위 밖으로 제외. 참조 확산 시 `shared/enums/ExpectedReaction.java`로 동일 패턴 이관 가능.
- **디자인 완성 후 재개**: 프론트엔드 디자인 완성 전까지 M2 프론트엔드·DigestDispatchJob 구현 보류.

### 미완료
- **DigestDispatchJob**: DAILY_1/DAILY_2/WEEKLY 빈도 묶음 발송 배치 — 디자인 완성 후 진행
- **M2 프론트엔드**: 로그인·포트폴리오·공시목록 페이지 — 디자인 완성 후 진행
- **Stage 3 RAG**: pgvector/Chroma 유사 공시 검색 — 디자인 완성 후 진행

---

## 2026-06-08 | M3 notification-retry-job Wave 1+2 — RetryJob + ChannelSender 분리 + 통합 테스트

**Spec**: `docs/specs/Approved/notification-retry-job.md` (Wave 1+2 완료)

### 완료
- `V15__add_notification_message.sql` — `message_body TEXT`, `message_subject VARCHAR(200)` 추가. RetryJob이 Disclosure·AnalysisResult 재조회 없이 재발송.
- `NotificationEntity` — messageBody/messageSubject 필드 + `storeMessage()`. markRetrying() 삭제(JPQL이 단독 소유).
- `ChannelSender` (신규) — Dispatcher·RetryJob 공유 발송 로직 단일 진실 소스. sendKakao/sendEmail/markUnsupported 이관.
- `NotificationDispatcher` 수정 — ChannelSender 주입. 일시적 채널 오류 시 FAILED 기록 대신 PENDING 유지 → RetryJob 처리.
- `NotificationRepository` — `findRetryTargets` JPQL(Pageable 100건 제한), `markAsRetrying` CAS UPDATE(`@Modifying(clearAutomatically=true)`).
- `NotificationRetryService` (신규) — `@Transactional` self-invocation 해결 위해 retryOne 분리. MAX_RETRY=3, RETRY_STATUSES 공개 상수.
- `NotificationRetryJob` — `@Scheduled(fixedDelay=300s)`, BATCH_SIZE=100, 건별 try-catch, `@ConditionalOnProperty`.
- `NotificationRetryJobIntegrationTest` — 10 케이스: PENDING→SENT, RETRYING→SENT, retryCount<MAX→RETRYING유지, MAX-1→FAILED확정, pre-V15 null→FAILED, already-SENT→skip, 배치3건, findRetryTargets FAILED/MAX_RETRY제외, soft-delete유저→FAILED.
- dc-review-code 리뷰 게이트: Critical 2건·High 3건·Medium 2건 해결 후 Green.
- 79/79 테스트 통과 (기준선 69 → 79).

### 결정 (코드에 드러나지 않는 사항)
- **Dispatcher 일시적 오류 전략**: `channelSender.send()` 예외 시 FAILED 기록 제거 → record PENDING 유지. RetryJob이 5분 이내 PENDING 픽업. 영구 실패(전화번호 없음, TELEGRAM 미지원)는 ChannelSender 내부 `markFailed()` + save() 처리 — Dispatcher catch 불도달.
- **self-invocation 패턴**: `NotificationRetryJob.retryFailedNotifications()` → `this.retryOne()` 호출 시 Spring 프록시 우회 → `@Transactional` 무효. NotificationRetryService를 별도 빈으로 분리해 프록시 경유 보장.
- **ChannelSender 비대칭 계약 허용**: 영구 실패(복호화 오류, blank phone)는 내부 markFailed + 정상 반환. 일시적 실패(API throw)는 전파. 호출자가 일시적/영구 구분 불가 문제는 Wave 4+ 타입 예외 계층(TransientChannelException) 도입으로 해소 예정.
- **notification-retry-job Spec Done 전환 가능**: Wave 1+2 완료 → `/dc-spec-move notification-retry-job Done` 필요.

### 미완료
- `/dc-spec-move notification-retry-job Done` — Spec 상태 Approved → Done 전환
- `/dc-spec-move user-auth-jwt-oauth2 Done` — Wave 2 이월
- **Sentiment → shared 이관**: cross-domain tech debt (notification→analysis 직접 참조)
- **TransientChannelException 계층**: ChannelSender 발송 실패 타입 분류 (일시적 vs 영구) — Wave 4+ 도입
- **ShedLock**: 다중 인스턴스 배포 시 RetryJob 분산 락 (MVP 단일 인스턴스 허용)
- **MAX_RETRY 외부화**: `application.yml` `dartcommons.notification.max-retry` 프로퍼티

---

## 2026-06-08 | M3 notification-dispatcher Wave 3 — 통합 테스트

**Spec**: `docs/specs/Approved/notification-dispatcher.md` (Wave 3 완료)

### 완료
- `NotificationDispatcherIntegrationTest` — 11개 통합 테스트 (Testcontainers PostgreSQL + MockitoBean 채널 클라이언트)
  - INSTANT 4단계 필터 전 경로: withheld / notifyEnabled / typeFilter(POSITIVE_ONLY·NEGATIVE_ONLY) / frequency(DAILY_1)
  - dedup: 동일 (user, disclosure, channel) 2회 이벤트 → DB 1건 기록 확인
  - 채널 라우팅: KAKAO 전화번호 있음(SENT) / 없음(FAILED) / EMAIL(SENT)
  - 신뢰도 낮음(confidence<0.5) → '판단 보류' 메시지 포함 확인
  - 포트폴리오 미보유 공시 → 발송 없음 확인
- dc-review-code Green (A+ 종합, Critical 0 / High 0 / Medium 0)
  - Medium 2건 수정: Thread.sleep(700) → Awaitility during(500ms)/atMost(2s), nanoTime → UUID substring

### 결정 (코드에 드러나지 않는 사항)
- **부정 테스트 Awaitility 패턴**: `during(500ms).atMost(2s).until(isEmpty)` — 500ms 동안 지속적으로 비어있음을 검증. CI 환경 Thread.sleep flaky 방지.
- **Wave 3 범위 확정**: off_hours_allowed 필터 테스트는 TradingHoursUtil Clock 주입 도입 후 별도 Wave. TELEGRAM 채널 테스트는 실 구현 후 추가.
- **M3 Wave 3 완료** — notification-dispatcher Spec Wave 1~3 완료.

### 미완료
- **RetryJob**: PENDING/RETRYING 상태 재발송 배치 (sent_at IS NULL 이중 발송 방지)
- **Sentiment → shared 이관**: cross-domain 의존 tech debt 해소
- **카카오 실계정 endpoint 검증**: 실계정 승인 후 SEND_PATH + AlimtalkRequest 필드 검증
- `/dc-spec-move user-auth-jwt-oauth2 Done` — Wave 2 미완료 이월

---

## 2026-06-08 | M3 notification-dispatcher Wave 2 — 디스패처 코어

**Spec**: `docs/specs/Approved/notification-dispatcher.md` (Wave 2 완료)

### 완료
- `NotificationEntity` — notifications(V6) JPA 엔티티. Channel/Status enum, markSent/markFailed, 500자 truncate
- `NotificationRepository` — `findByUserId` + `findByStatus` (idx_notifications_status 부분 인덱스 활용)
- `NotificationMessageBuilder` — 채널 무관 본문/제목 조립. confidence<0.5 "판단 보류" 삽입, 면책문구 고정
- `NotificationDispatcher` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("notificationExecutor")`. 4단계 INSTANT 필터(enabled→type_filter→off_hours→INSTANT) + 채널 라우팅(KAKAO/EMAIL/TELEGRAM). uq_notification_dedup DataIntegrityViolationException 멱등 처리. 유저별 try-catch 격리.
- dc-review-code Green (Critical 0, High 0 / A- 종합)

### 결정 (코드에 드러나지 않는 사항)
- **DataIntegrityViolationException dedup 패턴**: SimpleJpaRepository.save()는 자체 TX. 위반 예외 catch 후 skip이 caller TX 오염 없이 안전. 각 save()는 독립 TX.
- **cross-domain 한시 허용**: `notification` → `disclosure.entities.Disclosure` + `analysis.entities.AnalysisResult.Sentiment` 직접 참조. CLAUDE.md §3-2 위반이나 MVP 한시 허용(주석 명시). Sentiment → shared 이관 + Disclosure 요약 → event payload로 해소 예정.
- **N+1 쿼리 MVP 허용**: 보유자별 userRepository 단건 조회. 인기 종목 대량 보유자 시 개선 필요 — Wave 3 RetryJob 설계 시 `findAllById()` 일괄 조회로 전환 검토.
- **발송 후 상태 업데이트 실패**: 발송 성공 후 markSent save() 실패 시 record가 PENDING 잔류. RetryJob은 PENDING 재발송 전 sent_at IS NULL 체크로 이중 발송 방지 필요.
- **TELEGRAM**: MVP 미지원 → FAILED 상태로 기록. 후속 Spec에서 텔레그램 채널 추가 시 markUnsupported 분기 제거.

### 미완료
- **Wave 3**: `NotificationDispatcherIntegrationTest` (Testcontainers PostgreSQL, MockBean KakaoAlimtalkClient + MailNotificationClient). 4단계 필터 단위 테스트 포함.
- **RetryJob**: Wave 3+ PENDING/RETRYING 상태 재발송 배치 (sent_at IS NULL 체크로 이중 발송 방지)
- 카카오 알림톡 실계정 승인 후 endpoint/request body 검증 필요

---

## 2026-06-08 | M3 notification-dispatcher Wave 1 — 알림 인프라 기반

**Spec**: `docs/specs/Approved/notification-dispatcher.md` (Wave 1 완료)

### 완료
- `build.gradle` — `spring-boot-starter-mail` 추가
- `KakaoAlimtalkProperties` + `KakaoAlimtalkClient` (RestClient + `@Retryable`, HostWhitelist SSRF 방어)
- `MailNotificationProperties` + `MailNotificationClient` (JavaMailSender 래퍼, `MailSendException`, `isDebugEnabled()` guard)
- `TradingHoursUtil` — KRX 09:00~15:30 KST 판단 (`shared/util/`, private 상수)
- `ExecutorConfig.notificationExecutor` 빈 추가 (core=2, max=4, queue=500)
- `HostWhitelist.PROD_ALLOWED` — `alimtalk-api.kakao.com` 추가
- `application.yml` — `dartcommons.kakao.alimtalk.*` + `spring.mail.*` + `dartcommons.mail.from` 추가
- `test/resources/application.yml` — Kakao/mail 더미값 추가, `.env` import **제거** (보안)
- dc-review-code Green 통과 (NPE guard, private record, MailSendException, 이메일 마스킹 일관화 수정 포함)

### 결정 (코드에 드러나지 않는 사항)
- **카카오 알림톡 endpoint 미검증 허용**: `SEND_PATH="/v1/message/send"` + `AlimtalkRequest` 필드명은 "확인 필요" 상태로 Wave 1 머지. 카카오 비즈메시지 계정 승인 전 실검증 불가 — Wave 2에서 실계정 확인 후 확정.
- **test yml .env import 제거**: 로컬 `.env`의 실 운영 키가 테스트 더미값을 덮어쓰는 보안 위험 제거. bootRun의 `.env` 로드(build.gradle Gradle task)는 영향 없음.
- **ExecutorConfig 거절 정책**: `ThreadPoolTaskExecutor` 기본은 AbortPolicy (CallerRunsPolicy 아님). Wave 2 NotificationDispatcher 구현 시 이벤트 리스너 스레드 블로킹 방지를 위해 명시적 CallerRunsPolicy 추가 검토.
- **maxRetries property 미반영**: `KakaoAlimtalkProperties.maxRetries`가 `@Retryable(maxAttempts=3)` 리터럴로 하드코딩됨. DartClient와 동일 패턴 — RetryTemplate 전환 시 해결.

### 미완료
- **Wave 2**: `NotificationEntity` + `NotificationRepository` + `NotificationMessageBuilder` + `NotificationDispatcher` (`@TransactionalEventListener(AFTER_COMMIT)` + `@Async` + INSTANT 4단계 필터 + 채널 라우팅)
- **Wave 3**: `NotificationDispatcherIntegrationTest` (Testcontainers, MockBean 채널 클라이언트)
- 카카오 알림톡 실계정 승인 후 endpoint/request body 검증 필요

---

## 2026-06-02 | stocks 도메인 도입 + disclosure N+1 해소

**Spec**: `docs/specs/Approved/stocks-master-seed.md` (Wave 2 핵심)

### 완료
- `stocks` 도메인 신규 도입 (`Stock` 엔티티 V2 매핑, `StockRepository`)
- `StockRepository.findAllStockCodes()` — 배치당 1회 Set 로드로 deferred HIGH N+1 해소
- `KrxApiProperties` 스켈레톤 + `application.yml` `dartcommons.krx.*`
- `DisclosureCollectionService` — JdbcTemplate 제거, StockRepository 의존, `collectSingle` 시그니처 변경(coveredCodes Set 파라미터)
- `DisclosureCollectionIntegrationTest` — fixture를 `stockRepository.save()`로 전환 (6/6 통과)
- `review-findings-deferred.md` — N+1 항목 제거

### 결정 (코드에 드러나지 않는 사항)
- **아키텍처 예외 (CLAUDE.md §3-2)**: 마스터 데이터 도메인(`stocks/`)은 다른 도메인이 read-only로 직접 의존 허용. write는 마스터 도메인 내부 한정. 옵션 B(shared facade)는 indirection만 늘려 기각.
- **market 필드**: String 유지 (enum 미사용). V10 시드/SyncJob과 동시 도입 예정.
- **KRX 키 발급 완료**, 출처: KRX 공개 CSV (data.krx.co.kr)

### 미완료 (다음 세션)
stocks-master-seed Wave 1 + 3년치 백필 가능 상태:
- `#1` KRX 실측 (엔드포인트/파라미터 확정) + `api_spec.md` §3.2 갱신
- `#3` `DartCorpCodeClient` (corpCode.xml zip 다운로드 + 파싱)
- `#4` `KrxClient` (RestClient + 종목 기본정보)
- `#5` `scripts/data_collection/seed_stocks.py` (KRX 공개 CSV + DART 매핑 → V10 SQL 출력)
- `#7` `V10__seed_stocks.sql` (스크립트 결과 → 적용)
- `#8` `StockMasterService` (upsert 오케스트레이터)
- `#9` `StockMasterSyncJob` (@Scheduled 분기 1회)
- **백필 진입점**: `BackfillRunner` 또는 REST `POST /admin/disclosures/backfill` (3년치 수집 가능해짐)
- **DART 윈도우 청크 분할**: 3개월 단위 + 속도 제어
- **DB 청크 적재 최적화**: 건별 save → saveAll 배치

### deferred 잔여 (review-findings-deferred.md)
- HIGH: `Thread.sleep` 블로킹 재시도 (spring-retry 의존성 추가 결정 후)
- HIGH: `disclosure` → `infrastructure` DTO 직접 의존 (아키텍처 리팩 Spec)
- MEDIUM: `lastPolledDate` 인메모리 (멀티 인스턴스 전환 시점)
- MEDIUM: SSRF 화이트리스트 부재
- LOW: API 키 에러 로그 노출

---

## 2026-06-02 | Wave 1 + 3: stocks 시드 파이프라인 + 동기화 잡

**Spec**: `docs/specs/Approved/stocks-master-seed.md` (Wave 1 #3/#4/#5 + Wave 3 #8/#9)

### 완료
- `scripts/data_collection/seed_stocks.py` — pykrx로 KOSPI200/KOSDAQ150 인덱스 구성 + DART corpCode 매핑 → V10 SQL 자동 생성 (`ON CONFLICT DO UPDATE` 멱등)
- `scripts/data_collection/requirements.txt` (pykrx, requests) + `README.md` 실행 가이드
- `DartCorpCodeClient` — corpCode.xml zip 다운로드 + StAX 스트리밍 파싱(메모리 효율) + zip 매직넘버 검사로 JSON 에러 응답 분기
- `KrxClient` skeleton — Spec 카드 #1 실측 placeholder, `fetchAllBasicInfo()`는 빈 Map 반환 + WARN 로그
- `StockMasterService` — DART corp_code + KRX 기본정보 결합해 기존 stocks 행 upsert (신규 편입 종목 자동 추가 안함 — V10/별도 정책 분리, 편입 제외 종목 삭제 안함 — portfolios FK 보호)
- `StockMasterSyncJob` — `@Scheduled(cron = "0 0 4 1 1,4,7,10 *" KST)` 분기 1회, 예외 비중단
- 통합 테스트 7/7 통과 (DartcommonsApplicationTests + DisclosureCollectionIntegrationTest 6건)

### 결정 (코드에 드러나지 않는 사항)
- **V10 시드 SQL은 코드에 미포함** — 스크립트 실행 산출물(`seed_stocks.py` 산출). 사용자가 1회 실행 → SQL 생성 → 커밋 흐름.
- **분기 SyncJob은 기존 행 갱신만** — 신규 편입은 V10 마이그레이션(또는 후속 분기 V{n}), 편입 제외 삭제는 후속 Spec(사용자 알림 + portfolios FK 마이그레이션).
- **KrxClient placeholder**: #1 실측 미완료. 빈 Map 반환 + WARN 로그. SyncJob 호출되더라도 DART corp_code만 갱신, sector 무변화. #1 실측 후 KrxApiProperties endpoint 필드 추가하고 placeholder만 교체하면 됨(컴파일 영향 최소화).
- DART corpCode 응답 zip 매직넘버(`0x50 0x4B`) 검사로 JSON 에러 응답(키 오류 등) 빠르게 분기.

### 미완료 (다음 세션)
**남은 카드**:
- `#1` KRX 정확한 엔드포인트 실측 + `api_spec.md` §3.2 갱신 (네트워크 호출 필요)
- `#7` 사용자가 `seed_stocks.py` 실행 → V10 SQL 생성 → 커밋
- `#12` `feature_structure.md` §4 / `api_spec.md` §3.2 / `db_schema.md` 갱신

**3년치 백필 진입점 (별도 Spec 권장)**:
- `BackfillRunner` 또는 REST `POST /admin/disclosures/backfill?from=&to=`
- DART 윈도우 3개월 청크 분할 + DB saveAll 배치
- `Thread.sleep` 블로킹 재시도 → `spring-retry` 도입 (deferred HIGH 동시 해결)

---

## 2026-06-02 | spring-retry + BackfillService + KrxClient 실구현

**Spec**: `docs/specs/Approved/stocks-master-seed.md` (#1 KRX 부분) + 신규 백필 경로

### 완료
- **spring-retry 도입** (build.gradle: `spring-retry` + `spring-aspects`, `SchedulingConfig`에 `@EnableRetry`)
- **`DartClient` 리팩토링** — `Thread.sleep` 제거. 페이지 호출은 `DartPageFetcher` 내부 `@Component`로 분리(AOP 프록시 제약 회피) + `@Retryable(maxAttempts=3, exponential backoff)`. **deferred HIGH 해결**
- **`KrxClient` 실구현** — pykrx 검증 엔드포인트(`data.krx.co.kr/comm/bldAttendant/getJsonData.cmd`, bld=`MDCSTAT01901`) POST 폼 호출 + JSON `OutBlock_1` 파싱. `@Retryable`. 검증 미완료 명시(비공식 API).
- **`DisclosureBackfillService`** — 90일 청크 분할(`WINDOW_DAYS=90`), 청크별 트랜잭션, saveAll 배치(`CHUNK_SIZE=500`), 이벤트 발행 옵션(기본 false — 백필 시 analysis 큐잉 폭주 방지)
- **`DisclosureBackfillController`** — `POST /admin/disclosures/backfill?from=&to=&emitEvents=false`
- **`application.yml`** — `hibernate.jdbc.batch_size=500` + `order_inserts/updates=true`
- **`api_spec.md` §3.2 갱신** — KRX 공개 데이터 API 패턴 + 검증 필요 마커. DART list.json 표에 백필 라인 추가
- **`DisclosureBackfillServiceTest`** — 청크 분할 / 커버 필터 / 500+ 배치 / 멱등 (4건 통과)
- 전체 테스트 **11/11 통과** (Application + Disclosure 6 + Backfill 4)

### 결정 (코드에 드러나지 않는 사항)
- **백필 경로는 이벤트 미발행 기본** — 3년치 65k+ 건이 분석 도메인 큐로 쏟아지면 폭주. 백필 후 analysis는 별도 배치로 트리거 권장.
- **`DartPageFetcher` 내부 클래스 분리** — `@Retryable`은 AOP 프록시 기반이라 same-class self-invocation 우회. 별도 `@Component`로 분리해 정상 작동.
- **`KrxClient` 패턴은 pykrx 기반 추정** — KRX `data.krx.co.kr`는 비공식 인터페이스라 사전 공지 없이 변경 가능. 운영 환경에서 실측 1회 권장(통합 테스트 또는 curl). 시드 산출은 pykrx에 위임(seed_stocks.py)해 안정성 확보.
- **`BackfillController` 인증 미적용** — Spring Security 미도입 상태. 운영 배포 전 `@PreAuthorize("hasRole('ADMIN')")` 필수.

### 운영 사용 흐름
```bash
# 1. stocks 시드
cd scripts/data_collection && python seed_stocks.py
git add backend/src/main/resources/db/migration/V10__seed_stocks.sql && git commit -m "..."
./gradlew bootRun

# 2. 3년치 백필 (별도 터미널)
curl -X POST "http://localhost:8080/admin/disclosures/backfill?from=2023-06-01&to=2026-06-01&emitEvents=false"
# → 13개 청크 × 평균 ~5k건 = ~65k건 적재, DART rate limit 여유
```

### 미완료
- KRX 운영 실측 (사용자 환경에서 1회 검증 후 `KrxClient.parseResponse()` 필드명 조정)
- `BackfillController` 인증 가드 (Spring Security 도입 시점)
- 비동기 백필 (현재 동기 — 큰 범위는 HTTP 타임아웃 위험)
- KRX 일별 시세 + Stage 3 5일 반응 (Spec 분리)

---

## 2026-06-02 | deferred 전건 해결 + 보안 게이트

**Spec**: 모든 deferred 항목 마무리 (`review-findings-deferred.md` status: open → resolved)

### 완료
- **LOW**: `SecretMasker` 유틸 + 외부 클라이언트 예외 메시지 마스킹 (`crtfc_key=...` → `***`)
- **MEDIUM**: `HostWhitelist` 유틸 + `DartClient`/`DartCorpCodeClient`/`KrxClient` 생성자에서 `baseUrl` 호스트 검증 (prod: `opendart.fss.or.kr`, `data.krx.co.kr` / test: localhost)
- **MEDIUM**: V11 `system_configs` 마이그레이션 + `SystemConfig` 엔티티/`SystemConfigRepository`. `DisclosurePollingJob` `AtomicReference` → DB 영속화 + 재기동 복원
- **HIGH**: `disclosure/dto/RawDisclosureItem` 도메인 DTO 도입. `DartClient.fetchList()` 반환 타입 변경, `DisclosureCollectionService`/`DisclosureBackfillService`/`DisclosurePollingJob` 모두 `RawDisclosureItem`만 의존. 변환 책임은 infrastructure(`DartClient.toDomain`)
- **보안 게이트**: `spring-boot-starter-security` + `SecurityConfig`(HTTP Basic `/admin/**` 인증). `AdminAuthProperties`(env 주입). in-memory user. `AdminAuthIntegrationTest` 3건(401·401·200) 통과
- 통합 테스트 **14/14 통과** (Application 1 + Disclosure 6 + Backfill 4 + AdminAuth 3)

### 결정 (코드에 드러나지 않는 사항)
- **DTO 리팩토링 범위**: `DartListResponse.Item`은 infrastructure 내부에 그대로 보존(역직렬화용). 도메인이 가져가는 형태만 `RawDisclosureItem`으로 분리. infrastructure가 변환 책임.
- **SSRF 화이트리스트**: profile-gated 대신 단일 화이트리스트 + test 호스트 함께 보유 — 단순함 우선. 운영에서 test 호스트 시도 시 부팅 실패는 의도된 빠른 실패.
- **`system_configs`는 shared/config에 위치**: 다른 도메인 잡 상태도 공유 가능. `config_key` 네이밍 컨벤션 `<domain>.<key>` 권장.
- **Spring Security MVP**: in-memory user 1명(`admin`) + HTTP Basic + STATELESS. JWT는 user 도메인 도입 시점에 교체. `/admin/**` 외에는 permitAll 유지(공시 통역 사용자 라우트와 충돌 없음).
- **`ADMIN_PASSWORD` 미설정 시**: Spring placeholder 미해결로 그대로 문자열 binding → @NotBlank 통과 → 인증은 항상 실패(literal 비교). 운영에선 강한 무작위 env 주입 필수.

### 운영 사용 흐름 (변경)
```bash
# 환경변수 추가
export ADMIN_USERNAME=admin
export ADMIN_PASSWORD=<strong-random-32+chars>

# 백필 호출 — HTTP Basic 인증 필수
curl -X POST -u admin:<password> \
  "http://localhost:8080/admin/disclosures/backfill?from=2023-06-01&to=2026-06-01&emitEvents=false"
```

### 미완료 (다음 작업)
- KRX 운영 실측 (사용자 환경에서 1회 검증 후 `KrxClient.parseResponse()` 필드명 조정)
- KRX 일별 시세 + Stage 3 5일 반응 (별도 Spec)
- user 도메인 + JWT (`/admin/**` Basic 가드 교체) — 별도 Spec
- analysis 도메인 (Stage 2~5 LLM 분석) — 별도 Spec, `DisclosureCollectedEvent` 소비자

---

## 2026-06-02 | 비동기 백필 잡

**Spec**: (없음 — 단일 PR 단순 작업)

### 완료
- **V12 `backfill_jobs`** 마이그레이션 (status CHECK enum, chunks_total/done, fetched/saved, timestamps, error_message)
- **`BackfillJob` 엔티티** + `BackfillJobRepository` (UUID jobId 외부 키)
- **`BackfillJobService`** — `createJob()` PENDING 생성, `@Async runAsync()` 별도 스레드 실행, `Propagation.REQUIRES_NEW`로 청크당 진행률 갱신
- **`DisclosureBackfillService`** — `ChunkProgressListener` 콜백 추가 (오버로드로 기존 시그니처 유지), `calculateChunks()` 정적 메서드
- **컨트롤러 비동기 엔드포인트** — `POST /admin/disclosures/backfill/jobs` 202 + jobId, `GET .../jobs/{id}` 상태 조회 (NotFound 404)
- **`SchedulingConfig`에 `@EnableAsync`** 추가
- 통합 테스트 **21/21 통과** (기존 14 + BackfillJobService 3 + BackfillJobController 4)

### 결정 (코드에 드러나지 않는 사항)
- **TaskExecutor 빈 미설정** — Spring 기본 `SimpleAsyncTaskExecutor`(매 호출 새 스레드). 운영 부하 클 경우 `ThreadPoolTaskExecutor` 빈 추가로 동시 잡 수 제한.
- **잡 cleanup/중단 미구현** — 후속 작업 (운영 정책 결정 후).
- **동시 백필 중복 방지 없음** — 같은 범위 두 번 호출 시 두 잡 생성. `rcept_no` 멱등으로 데이터 안전성은 보장.
- **`runAsync` 호출은 컨트롤러에서 직접** — `createJob` 끝나자마자 트리거. PENDING→RUNNING 사이에 짧은 갭 존재(허용 범위).

### 운영 사용 흐름 (3년치 백필 비동기)
```bash
# 1. 잡 생성 — 즉시 202 + jobId
curl -X POST -u admin:<password> \
  "http://localhost:8080/admin/disclosures/backfill/jobs?from=2023-06-01&to=2026-06-01&emitEvents=false"
# → {"jobId":"...", "status":"PENDING", ...}

# 2. 진행률 조회 — 5분마다 폴링
curl -u admin:<password> \
  "http://localhost:8080/admin/disclosures/backfill/jobs/<jobId>"
# → {"status":"RUNNING", "chunksDone":7, "chunksTotal":13, "saved":35000, ...}
```

---

## 2026-06-07 | M2 user-auth Wave 4 — OAuth 2.0 Kakao/Google/Naver

**Spec**: `docs/specs/Approved/user-auth-jwt-oauth2.md` (Wave 4: Cards 15-16)

### 완료
- **OAuthProperties** — @ConfigurationProperties("dartcommons.oauth") record. kakao/google/naver 3중첩 record. application.yml 환경변수 섹션 추가
- **OAuthProviderClient** (interface) — getProviderName / buildAuthorizationUrl / getUserInfo(code, state)
- **KakaoOAuthClient** — kauth.kakao.com 토큰 + kapi.kakao.com/v2/user/me. RestClient(timeout 5s/10s) + @Retryable(noRetryFor=4xx)
- **GoogleOAuthClient** — oauth2.googleapis.com/token + googleapis.com/userinfo. 동일 패턴
- **NaverOAuthClient** — nid.naver.com/oauth2.0/token(state 포함) + openapi.naver.com/v1/nid/me. 동일 패턴
- **AuthService** OAuth 메서드 추가: getOAuthAuthorizationUrl(state CSRF Caffeine TTL 5min) + oauthCallback(state검증→getUserInfo→oauth_id 조회→로그인/자동가입)
- **AuthController** OAuth 엔드포인트: `GET /api/v1/auth/oauth/{provider}/url` + `POST /api/v1/auth/oauth/{provider}/callback`
- **DTO 2종**: OAuthCallbackRequest(@NotBlank code+state), OAuthUrlResponse(url+state)
- dc-review-code **Green** (HIGH 1건 수정: concurrent signup DataIntegrityViolationException→409, MEDIUM 2건: timeout 추가, toOAuthProvider valueOf 단순화)

### 결정
- **state CSRF 저장소**: Caffeine 캐시(in-process) 선택. Redis 불필요(단일 인스턴스 MVP). 수평 확장 시 Redis 전환 필요.
- **OAuth 이메일 충돌 정책**: 동일 이메일로 이메일 가입 선행 시 409 반환("이메일 로그인 이용"). 자동 계정 연동 미지원(보안·UX 트레이드오프).
- **OAuth 자동가입 동의**: TERMS/PRIVACY/DISCLAIMER=true, MARKETING=false 기본 처리. 자본시장법 §11 면책조항 포함.
- **OAuthProviderClient 인터페이스에 state 파라미터**: Naver 토큰 교환에 state 필수 → 인터페이스에 포함. Kakao/Google은 무시.
- **SecurityConfig 무변경**: `/api/v1/auth/**` 이미 permitAll 커버.

### 미완료 (Wave 5)
- `AuthIntegrationTest` (Testcontainers PostgreSQL) — signup/login/refresh/logout 흐름 검증
- `PortfolioIntegrationTest` — IDOR 403, Free 422, AES decrypt 검증
- Spec `user-auth-jwt-oauth2` → **Done** 전환 (`/dc-spec-move user-auth-jwt-oauth2 Done`)

---

## 2026-06-07 | M2 user-auth Wave 3 — 사용자·포트폴리오·알림설정·종목검색

**Spec**: `docs/specs/Approved/user-auth-jwt-oauth2.md` (Wave 3: Cards 12-14)

### 완료
- **UserService** + **UserController** — `GET/PATCH/DELETE /api/v1/users/me` (soft delete + 전 기기 로그아웃)
- **PortfolioService** (CRUD + Free 3종목 제한 + AES-256-GCM 암복호 + IDOR 방지) + **PortfolioController** — `GET/POST/PUT/DELETE /api/v1/portfolios`
- **NotificationSettingsService** + **NotificationSettingsController** — `GET/PUT /api/v1/users/me/notifications`
- **StockSearchController** — `GET /api/v1/stocks/search?q=` PUBLIC (max 20건, @Validated @RequestParam)
- **DTO 6종**: UserMeResponse, UpdateMeRequest, PortfolioRequest, PortfolioResponse, NotificationSettingsRequest, NotificationSettingsResponse
- **StockRepository.search()** — JPQL LIKE 쿼리(corpName 포함·stockCode 포함, Pageable 20건)
- **GlobalExceptionHandler** — `ConstraintViolationException` 핸들러 + `AesGcmEncryptor.CryptoException` 핸들러 추가

### 결정
- **IDOR 패턴**: `findByIdAndUserId` 스코프 쿼리 → 미존재/권한없음 모두 403. 404와 구분 없음(정보 최소화 허용).
- **tier 추출**: JWT claims `ROLE_{TIER}` → `UserEntity.Tier.valueOf()` 인메모리 변환. DB 조회 없음.
- **PortfolioRequest 공유**: create/update 동일 DTO 사용. update 시 stockCode는 `PortfolioEntity.update()`에 전달되지 않아 변경 불가. stockCode 변경 필요 시 DELETE → POST 흐름 안내.
- **CryptoException 처리**: 500 내부 오류로 반환(AES_KEY 변경·DB 손상 시 발생). 스택 트레이스는 서버 로그에만 기록.

### 미완료 (Wave 4)
- `KakaoOAuthClient` + `GoogleOAuthClient` + `NaverOAuthClient` (RestClient + @Retryable)
- `AuthService` OAuth 콜백 (oauth_id 매칭 → 로그인/자동가입)
- `AuthController` OAuth 엔드포인트 (`GET /auth/oauth/{provider}/url`, `POST /auth/oauth/{provider}/callback`)

---

## 2026-06-07 | M2 user-auth Wave 2 — 이메일 Auth 서비스·컨트롤러·DTO

**Spec**: `docs/specs/Approved/user-auth-jwt-oauth2.md` (Wave 2)

### 완료
- **DTO 4종**: `SignupRequest`(@AssertTrue terms/privacy/disclaimer 필수, @Email, @Size 8-72), `LoginRequest`, `AuthResponse`(@JsonProperty snake_case, expiresIn), `RefreshRequest`
- **GlobalExceptionHandler** (shared/exception) — `@Valid` 실패 → RFC 7807 ProblemDetail 필드별 메시지
- **ConsentService** — `recordSignupConsents` saveAll batch 4건 INSERT, `findLatest`
- **AuthService** — signup(BCrypt+consent 동일 트랜잭션), login(계정열거방지 동일 401), refresh(rotation: deleteOld+saveNew), logout(멱등), forceLogout(deleteByUserId)
- **AuthController** — `POST /api/v1/auth/{signup/login/refresh/logout}`, signup→201, logout→204
- **application.yml** — `spring.mvc.problemdetails.enabled: true`
- 리뷰 수정: ConsentService saveAll batch (Medium 1건 즉시 반영)

### 결정
- **계정 열거 방지**: 이메일 미존재·비밀번호 불일치 모두 `"인증 실패"` 동일 응답
- **refresh token 응답**: body에 raw 값 반환(프론트 보안 저장소 책임). HttpOnly Cookie 전환은 프론트 협의 후
- **logout 멱등**: 미존재 hash 삭제 시 조용히 성공(클라이언트 재시도 안전)
- **만료 토큰 클린업**: Wave 5 통합 테스트 시 `@Scheduled + deleteExpiredTokens()` 추가 예정

### 미완료 (Wave 3)
- `UserService` + `UserController` (me/update/soft-delete) + `UserMeResponse`/`UpdateMeRequest` DTO
- `PortfolioService` (CRUD + Free 3종목 제한 + AES 암복호) + `PortfolioController` + DTO
- `StockSearchController` (`GET /api/v1/stocks/search?q=` PUBLIC)
- `NotificationSettingsService` + `NotificationSettingsController`

---

## 2026-06-07 | M2 user-auth-jwt-oauth2 Wave 1 — JWT + AES-256 + 사용자 도메인 인프라

**Spec**: `docs/specs/Approved/user-auth-jwt-oauth2.md` (Wave 1)

### 완료
- **build.gradle** — jjwt-api 0.12.6 (impl/jackson runtimeOnly) 추가
- **application.yml** — `dartcommons.jwt.*` / `dartcommons.crypto.*` 프로퍼티 추가
- **V14** `refresh_tokens` 마이그레이션 — `token_hash VARCHAR(64)` SHA-256 only, ON DELETE CASCADE
- **JwtProperties** + **CryptoProperties** — `@ConfigurationProperties` record + `@Validated` (미설정 시 부팅 즉시 실패)
- **JwtTokenProvider** — access token(HMAC-SHA256), raw refresh token(SecureRandom base64 48B), hashRefreshToken(SHA-256 hex), parseClaims
- **AesGcmEncryptor** — AES-256-GCM, IV 12B SecureRandom 매 호출 신규, `GeneralSecurityException` catch, null-safe
- **UserEntity** (OAuthProvider/Tier/NotifyChannel/NotifyFrequency/NotifyTypeFilter enum, soft delete, V1+V7 매핑)
- **PortfolioEntity** (avgBuyPriceEnc/quantityEnc BYTEA AES-256)
- **ConsentLogEntity** (ConsentType enum, INSERT-only)
- **RefreshTokenEntity** (token_hash SHA-256 전용, isExpired())
- **4 Repository** — UserRepository(soft-delete 쿼리), PortfolioRepository(userId 스코프+stock_code 역조회), ConsentLogRepository(findLatestByUserIdAndType), RefreshTokenRepository(deleteExpiredTokens @Transactional)
- **UserDetailsServiceImpl** — Spring Security 확장 포인트 (현재 미사용, form login 대비)
- **JwtAuthenticationFilter** — Bearer 토큰 추출 → 서명 검증 → SecurityContext principal=userId(Long). DB 조회 없음.
- **SecurityConfig 듀얼 체인** — @Order(1) /admin/** HTTP Basic, @Order(2) JWT Bearer + **401 AuthenticationEntryPoint** 추가(미인증→401, 미인가→403 구분)
- **src/test/resources/application.yml** — 테스트 전용 더미 JWT/AES 값
- 리뷰 수정 4건: SecurityConfig 401 EP, RefreshTokenRepository @Transactional, JwtProperties @Size(min=32), AesGcmEncryptor GeneralSecurityException

### 결정 (코드에 드러나지 않는 사항)
- **refresh token = SHA-256 해시만 DB 저장** — raw 토큰 미보관. DB 유출 시 원본 재사용 불가.
- **AES-GCM IV = 매 암호화 신규 생성** — 동일 평문도 매번 다른 결과(DB 정렬 불가 — 복호화 후 앱 계층에서만 연산).
- **듀얼 FilterChain 선택** — admin HTTP Basic과 user JWT를 같은 체인에 두면 Spring Security 6.4+에서 충돌. securityMatcher("/admin/**")로 격리.
- **UserDetailsServiceImpl 위치(shared/security)** — Spring Security 인프라가 user 레포에 의존. CLAUDE.md §3-2 역방향 금지 예외로 허용(Security 인프라 관행).
- **AES_KEY 관리** — 분실 시 기존 암호화 데이터 복호화 불가. 프로덕션은 AWS KMS/Vault DEK 패턴 권장.

### 미완료 → Wave 2에서 완료됨 ✅

---

## 2026-06-03 | 운영 환경 부팅 + 백필 완료 + OTHER 룰 보강

**산출**:
- **운영 배포**: docker-compose 포트 5433 분리, `.env` 자동 로드(build.gradle bootRun task), `application.yml` 기본값 5433, `docker-compose.yml`에 `${DB_PASSWORD}` 매핑
- **첫 부팅**: Flyway V1~V12 자동 적용 + V10 시드로 stocks 341건
- **3년치 백필**: `POST /admin/disclosures/backfill/jobs` 비동기 진입점 사용 — 13 청크 × 3.7시간 → **fetched 756,410건, saved 91,965건**
- **자동 폴링 검증**: `@Scheduled` 1분 폴링이 신규 공시 실시간 적재 중
- **DisclosureTypeClassifier 룰 16종 보강**: OTHER 61%(56,054건) → 8%(7,302건). **87% 회수**
- `scripts/data_collection/reclassify_other.sql` 일괄 재분류 적용

### 결정 (코드에 안 드러나는 사항)
- **포트 분리**: 다른 프로젝트(`gc-postgres` 5432) 충돌 회피. DartCommons는 5433 고정.
- **백필 비동기 채택**: 동기 호출은 3.7시간 → HTTP 타임아웃 위험. `@Async` + jobId + 진행률 영속화.
- **OTHER 8% 잔여는 의도적 비유지**: M1 Stage 2 LLM이 OTHER도 처리 예정 → 룰 보강 비용 대비 가치 낮음. diminishing returns.
- **분류 결과 분포**: EXECUTIVE_SHARE 15.5k가 1위 (임원·주요주주 거래 정상). 증권사 5개가 종목 TOP 5 (운용/매매 공시 빈번).

### 다음 세션 가장 큰 가치 — M1 Stage 2 LLM
9.2만 건 데이터에 호재/악재 라벨 부여. `/dc-plan analysis-stage2-llm`부터 시작.

---

## 2026-06-10 | Wave 4 — Phone·동의·요금제 통합 테스트 완성

**산출**:
- `PhoneVerifyIntegrationTest` 8케이스: happy(ArgumentCaptor OTP캡처)/잘못된코드400/만료410/rate-limit429(1분1회)/brute-force429(5회후차단)/noAuth401/형식오류400/전화번호형식400
- `ConsentIntegrationTest` 7케이스: signup후status조회/재동의204/status반영/버전형식400/NotBlank400/noAuth401×2
- `PricingIntegrationTest` 4케이스: PUBLIC200/3티어검증/필드완전성/FREE=0·PREMIUM>0
- `test/resources/application.yml`에 `pricing.plans` 추가 — 테스트 yml이 main을 대체하는 Spring Boot 동작으로 PricingProperties.plans()=null NPE 수정
- 전체 **139/139** 통과 (0 실패)

### 결정 (코드에 드러나지 않는 사항)
- **테스트 yml 주의**: Spring Boot 테스트 시 `src/test/resources/application.yml`이 `src/main/resources/application.yml`을 완전히 덮어씀(병합 아님). main에만 있는 설정은 테스트에서 누락됨 → 신규 `@ConfigurationProperties` 추가 시 테스트 yml에도 필수 값 동시 추가 필요.
- **Caffeine OTP 캐시 테스트 격리**: `PhoneVerificationService`의 rate limit 캐시(minuteRateCache/hourRateCache)는 Spring 컨텍스트 공유. 각 테스트가 uniqueEmail()로 새 userId를 생성해 키 충돌 없이 격리됨.

### 미완료 → 다음 세션
- `auth-email-verify` — 가입 플로우 블로커. signup/verify/page.tsx TODO 2건 잔존. Spec Draft 상태.
- `notification-read-status` — 알림 읽음 상태 DB 영속 (현재 Zustand 로컬 Set으로 임시 처리).

---

## 2026-06-11 | auth-email-verify — 이메일 OTP 가입 검증 완성

**산출**:
- `EmailVerificationService` — OTP 6자리 생성·발송(JavaMailSender), 5분 TTL Caffeine 캐시, 1분 rate limit, **brute-force 5회 차단**(OtpEntry record + AtomicInteger), **발송 실패 시 캐시 무효화**
- `EmailSendOtpRequest` / `EmailVerifyOtpRequest` DTO — Bean Validation(@Email, @Pattern 6자리)
- `AuthController` — `POST /auth/email/send-otp`, `POST /auth/email/verify` 2개 엔드포인트 추가 (permitAll 범위 `/api/v1/auth/**` 자동 적용)
- `AuthService.signup()` — R5 EMAIL_NOT_VERIFIED 422 가드 추가
- 기존 통합테스트 6개 — `@MockitoBean EmailVerificationService` + `@BeforeEach isEmailVerified=true` 추가 (기존 플로우 회귀 방지)
- FE `lib/api/auth.ts` — `useSendEmailOtp`, `useVerifyEmailOtp` 훅 추가
- FE `signup/page.tsx` — 이메일 제출 시 send-otp API 호출 → 409/429/기타 toast 분기
- FE `signup/verify/page.tsx` — TODO 2건 해소: `verifyEmailOtp.mutateAsync` + `sendEmailOtp.mutateAsync`(재전송), `isPending` 로딩 상태
- Spec Draft → Approved 이동
- 전체 **139/139** 통과

### 결정 (코드에 드러나지 않는 사항)
- **brute-force 방어 위치**: `verifyOtp()`에만 시도 카운터 적용. `sendOtp()`의 rate limit(1분 1회)은 별개 방어선. 5회 초과 시 캐시 무효화 → 사용자는 재전송 후 재시도.
- **발송 실패 처리**: `mailClient.send()` 예외 시 캐시 무효화 후 재전파. rate limit 카운터는 그대로 → 실패해도 1분 후에야 재시도 가능(의도된 동작 — 스팸 방지).
- **OAuth 경로 가드 미적용**: `AuthService.oauthCallback()`의 자동 회원가입에는 이메일 OTP 가드 없음 — OAuth 제공자가 이미 이메일 소유를 검증하므로 올바른 설계.
- **단일 인스턴스 가정**: `verifiedEmailCache`는 인메모리 — k8s 전환 시 Redis 이관 필요.

### 미완료 → 다음 세션
- `auth-email-verify` 전용 통합 테스트(`EmailVerifyIntegrationTest`) — `@MockitoBean MailNotificationClient` + ArgumentCaptor 패턴으로 작성 권장
- `notification-read-status` — 알림 읽음 상태 DB 영속 (FE Zustand 로컬 Set 임시 처리 상태)
