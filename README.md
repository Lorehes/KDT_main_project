---
type: context
status: active
created: 2026-05-28
updated: 2026-05-28
---

# DART 공시 통역 (DartCommons)

> **개인 투자자를 위한 AI 기반 공시 해석 & 실시간 알림 플랫폼**
>
> 보유 종목의 DART 공시를 실시간으로 받아 호재/악재 의미를 자연어로 해석해주는
> 개인 투자자용 AI 알림 서비스.

전체 컨텍스트 진입점은 [[HOME]], 서비스 기획 SSOT는 [[DART공시통역_통합기획서]],
개발 규칙은 [[CLAUDE]] 를 참고하세요.

---

## 1. 이 프로젝트는 무엇인가

개인 투자자는 보유 종목의 DART 공시를 받아도 **호재인지 악재인지 즉시 판단하기 어렵고**,
전문 용어·복잡한 서식 때문에 정보 접근 장벽이 높습니다. DartCommons는 이 문제를 해결합니다.

**핵심 가치 사슬**

| ① 수집 | ② 분류 | ③ AI 해석 | ④ 개인화 | ⑤ 전달 |
|--------|--------|-----------|----------|--------|
| DART OpenAPI 실시간 폴링 (1분) | 공시 유형 분류 / 종목 매핑 | LLM 호재/악재 판단 + 요약 | 보유 종목 필터 / 중요도 스코어링 | 카카오 알림톡 / 웹 대시보드 |

**분석 Stage (티어별 차등)** — 분석 깊이를 5단계로 나누고 요금제별로 제공 범위를 달리합니다.

| Stage | 내용 | Free | Pro | Premium |
|-------|------|:----:|:---:|:-------:|
| 1 | 룰 기반 추출 (회사명·수치·날짜 — LLM 변형 금지) | ✓ | ✓ | ✓ |
| 2 | LLM 호재/악재 분류 | ✓ | ✓ | ✓ |
| 3 | RAG 의미 검색 (Chroma) | – | ✓ | ✓ |
| 4 | LLM 최종 판단 | – | ✓ | ✓ |
| 5 | 재무/업황 결합 분석 | – | – | ✓ |

> 모든 분석은 **신뢰도(confidence)** 필드를 동반하고, 낮으면 "판단 보류"로 표시합니다.
> 분석 결과에는 항상 면책 문구 + "부정확함" 신고 경로가 따라붙습니다. (투자 권유 표현 금지 — 자본시장법 경계)

---

## 2. 기술 스택

| 계층 | 스택 |
|------|------|
| Backend | Java 21 (LTS) + Spring Boot 3.x, REST + (필요 시) WebSocket |
| Frontend | Next.js 15 (App Router) + TypeScript + TailwindCSS 4 + shadcn/ui |
| 빌드 | Backend: Gradle 8.x (Groovy DSL) / Frontend: pnpm 9.x |
| ORM / DB | Spring Data JPA + Hibernate, **Flyway 마이그레이션** / PostgreSQL |
| Vector DB | Chroma (RAG 임베딩, Stage 3) |
| LLM 통합 | LangChain4j 1.x — MVP: Ollama / 실서비스: OpenAI·Anthropic·Google |
| 상태/페칭(FE) | Zustand 5 + TanStack Query 5, 폼: React Hook Form + Zod |
| Auth | Spring Security + JWT, OAuth2 (Kakao/Google/Naver) |
| 알림 | 카카오 알림톡(1순위) → 폴백 텔레그램/이메일 |
| 외부 데이터 | DART OpenAPI(공시), KRX OpenAPI(주가), 공공 API(업황) |
| Test | BE: JUnit5 + Mockito + Testcontainers / FE: Vitest + Testing Library + Playwright |
| 코드 품질 | BE: Checkstyle + SpotBugs (Google Java Style) / FE: ESLint + Prettier |

---

## 3. 디렉터리 구조 (3분할)

> **루트 = Obsidian Vault + 문서 컨테이너**, **개발 코드는 backend/frontend/scripts 3분할로 격리**.

```
프로젝트루트/                        # Obsidian Vault root + git repo root
├── HOME.md                         # Vault 진입점 MOC
├── CLAUDE.md                       # Claude 호출에 자동 로드되는 프로젝트 규칙
├── README.md                       # 본 파일 (부트스트랩 안내)
├── docs/                           # 문서 컨테이너
│   ├── 기획서/                      # 서비스 기획 SSOT (통합기획서)
│   ├── 개발명세서/                   # api_spec · db · feature_structure · design_structure
│   ├── decisions/                  # 구현 전 결정 항목
│   ├── specs/{Draft,Approved,Done}/ # 기능 명세 (상태별)
│   ├── dev-log/{backend,frontend}.jsonl # 변경 로그
│   ├── ideas/                      # 아이디어/피드백
│   └── git-history/                # 마일스톤 요약
├── .claude/
│   ├── skills/                     # dc-* 스킬 16개 (§5)
│   └── agents/                     # 서브에이전트 정의
├── backend/                        # Spring Boot (Java 21)
│   ├── src/main/java/com/dartcommons/
│   │   ├── disclosure/             # 공시 수집·분류 (Stage 1)
│   │   ├── analysis/               # LLM 분석 (Stage 2~5, LangChain4j)
│   │   ├── notification/           # 알림 디스패처 (카카오/텔레그램/이메일)
│   │   ├── user/                   # 사용자·인증 (Spring Security + JWT)
│   │   ├── shared/                 # 공통
│   │   └── infrastructure/         # 외부 API (DART/KRX, WebClient)
│   ├── src/main/resources/db/migration/  # Flyway V{n}__*.sql
│   └── build.gradle
├── frontend/                       # Next.js 15
│   ├── src/app/                    # / signup login dashboard portfolios disclosures notifications pricing
│   ├── src/components/ · src/lib/
│   └── package.json
└── scripts/                        # Python 운영자용 일회성 (labeling/analysis/data_collection)
```

**작업 디렉터리 규칙**

- 백엔드 명령은 `backend/` 에서: `cd backend && ./gradlew test`
- 프론트 명령은 `frontend/` 에서: `cd frontend && pnpm install`
- 문서 경로 표기는 root 기준 유지 (`docs/dev-log/...`)
- 도메인 간 직접 의존 금지 — `shared/` 또는 이벤트 경유 (import 방향: shared → 도메인)

---

## 4. 처음 클론한 팀원 셋업 절차

### 4-0. 사전 준비물

| 도구 | 버전 | 비고 |
|------|------|------|
| JDK | 21 (LTS) | `java -version` 확인 |
| Node.js | 20+ | |
| pnpm | 9.x | `corepack enable pnpm` |
| PostgreSQL | 15+ | 로컬 또는 Docker |
| Docker | 최신 | Testcontainers 통합 테스트에 필요 |
| Ollama | 최신 | MVP Local LLM (선택) |

### 4-1. 클론

```bash
git clone <repo-url> KDT_mainPJ
cd KDT_mainPJ
```

### 4-2. 환경변수 설정

**키는 환경변수로만 주입** — 코드/`application.yml` 하드코딩 금지. `.env`·시크릿 파일은 커밋 금지.

```bash
# backend: src/main/resources/application-local.yml (gitignore) 또는 환경변수
export DART_API_KEY=...          # DART OpenAPI (공시)
export KRX_API_KEY=...           # KRX OpenAPI (주가)
export KAKAO_CLIENT_ID=...       # 카카오 알림톡 / OAuth
export OPENAI_API_KEY=...        # 또는 ANTHROPIC_API_KEY (실서비스 LLM)
export JWT_SECRET=...            # JWT 서명 키
export DB_URL=jdbc:postgresql://localhost:5432/dartcommons
export DB_USERNAME=... DB_PASSWORD=...
export AES_ENCRYPTION_KEY=...    # 매수가/수량 등 금융 개인정보 AES-256 암호화 키
```

```bash
# frontend: frontend/.env.local (gitignore)
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
```

### 4-3. 데이터베이스 준비

```bash
createdb dartcommons        # PostgreSQL DB 생성
cd backend
./gradlew flywayMigrate      # Flyway 마이그레이션 적용 (DDL은 Flyway만)
```

> `ddl-auto`는 `validate`/`none` 고정. `create/create-drop/update` 운영 금지.
> 적용된 마이그레이션 파일은 **불변** — 변경은 새 `V{n}__*.sql` 추가.

### 4-4. 백엔드 실행

```bash
cd backend
./gradlew bootRun            # http://localhost:8080
./gradlew test               # JUnit5 + Testcontainers (Docker 필요)
```

### 4-5. 프론트엔드 실행

```bash
cd frontend
pnpm install
pnpm dev                     # http://localhost:3000
pnpm test                    # Vitest
pnpm exec playwright test    # E2E
```

### 4-6. Obsidian Vault 열기 (선택)

루트를 Obsidian Vault로 열면 `[[wikilink]]`로 문서를 탐색할 수 있습니다.
진입점은 [[HOME]]. `.obsidian/` 개인 설정은 gitignore 대상입니다.

---

## 5. dc- 스킬 16개로 일하는 방법

이 프로젝트는 **Claude Code 스킬 16개**로 의도 → Spec → 구현 → 리뷰 → 배포 → 문서동기화를
파이프라인으로 진행합니다. 슬래시 명령 `/dc-<name>` 으로 호출합니다.

### 5-1. 개발 파이프라인 (핵심 흐름)

```
사용자 의도
 └▶ /dc-plan          → docs/specs/Draft/<slug>.md (리서치 + Spec 생성)
     └▶ /dc-tech-review → Spec 하단 ## Tech Review (작업 카드 분해, Draft→Approved 검토)
         └▶ /dc-spec-move <slug> Approved  (사용자 승인)
             └▶ /dc-implement → backend / frontend 코드 작성
                 ├▶ /dc-review-code     → 보안·품질 10축 리뷰 (P0 있으면 회귀)
                 ├▶ /dc-review-frontend → (FE 변경 시) Playwright UI 리뷰
                 ├▶ /dc-test-verify     → JUnit5/Testcontainers · Vitest/Playwright
                 └▶ /dc-push            → AI 채점 + WORKLOG + 커밋 + 푸시 (마지막 wave 시 Done 제안)
                      └▶ /dc-doc-sync   → docs 정합 + Obsidian wikilink/frontmatter/MOC 무결성
```

| 명령 | 용도 |
|------|------|
| `/dc-plan <의도>` | 의도 → 리서치 + `docs/specs/Draft/` Spec 생성 |
| `/dc-tech-review <spec>` | 아키텍처 분해 + 작업 카드 (Draft→Approved 검토) |
| `/dc-spec-move <slug> <상태>` | Spec 상태 전환 (Draft↔Approved↔Done) — frontmatter + `git mv` 일괄 |
| `/dc-implement <spec>` | BE/FE 코드 작성 (본문 → 메타 → 리뷰 게이트) |
| `/dc-review-code` | 변경 코드 10축 심층 리뷰 (보안/성능/품질/접근성/OWASP/...) |
| `/dc-review-frontend` | 프론트 UI 품질 리뷰 (Playwright PC+Mobile) |
| `/dc-test-verify` | 테스트 실행 (BE: JUnit5+Testcontainers / FE: Vitest+Playwright) |
| `/dc-push` | AI 채점 + WORKLOG + 커밋 + 푸시 (마지막 wave 시 Done 자동 제안) |
| `/dc-doc-sync` | docs 정합성 + Obsidian Vault 무결성(wikilink/frontmatter/MOC) 점검·갱신 |

### 5-2. 운영 · 총괄 · 세션 (파이프라인 외)

| 명령 | 용도 |
|------|------|
| `/dc-ops` | 프로젝트 운영 감사 (문서/스펙/SSOT/메모리/컴플라이언스, 읽기 전용) |
| `/dc-hemiunu` | 프로젝트 총괄 점검 (흐름/스킬/세션건강/개선/라이프사이클) |
| `/dc-handoff` | 세션 핸드오프 (다음 세션으로 맥락 이관) |
| `/dc-triage` | 피드백 분류 → GitHub Issue / docs/ideas |

### 5-3. 기획 · 마케팅

| 명령 | 용도 |
|------|------|
| `/dc-product-planner <기능>` | 제품/기능 기획 (경쟁분석, Go/No-Go) |
| `/dc-product-marketing <대상>` | 마케팅/IR/영업 자료 생성 (B2C/B2B/IR) |

### 5-4. 1회성 셋업

| 명령 | 용도 |
|------|------|
| `/dc-obsidian-init` | (최초 1회) Obsidian Vault 셋업 — HOME/섹션 MOC/frontmatter 마이그레이션 |

> 스킬 정의는 `.claude/skills/<name>/SKILL.md`, 서브에이전트는 `.claude/agents/*.md`.

---

## 6. 문서 규칙 (Obsidian Vault)

- 모든 새 `.md` 노트는 **frontmatter 4필드 필수**: `type` / `status` / `created` / `updated`
- 노트 참조는 wikilink `[[파일명]]` 사용 (상대경로보다 우선), 진입점은 [[HOME]]
- 새 노트 추가 시 해당 섹션 `README.md`(MOC) 와 [[HOME]] 에 한 줄 등재
- 기계 판독 필드(JSONL `spec`/`files` 등)는 상대경로 유지 (root 기준)
- 코드 변경마다 `docs/dev-log/{backend,frontend}.jsonl` 에 1줄 추가 → [[docs/dev-log/README|변경 로그 스키마]]
- 검증/등재 자동화: `/dc-doc-sync`(일상) / `/dc-obsidian-init`(1회성)

---

## 7. 절대 금지 (요약)

- `git push --force` (main/master), `git commit --no-verify`
- `.env`·시크릿 커밋 / DART·KRX·카카오·LLM API 키 하드코딩
- Hibernate `ddl-auto: create/create-drop/update` 운영 사용 (Flyway만)
- 매수가·보유 종목 평문 로깅 / 미암호화 저장
- **투자 권유 표현** ("매수/매도 추천", "수익 보장") — 자본시장법 경계
- LLM 응답 스키마 파싱 없이 직접 사용 / 신뢰도 없이 호재악재 단정
- Mock DB로 통합 테스트 종료 (Testcontainers 사용)
- 디자인 토큰 우회한 색·간격 하드코딩 / 면책 조항 없는 분석 결과 노출

> 전체 규칙은 [[CLAUDE]] §7 참고.
