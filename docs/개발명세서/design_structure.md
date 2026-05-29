---
type: doc
status: draft
created: 2026-05-30
updated: 2026-05-30
---

# 디자인 구조 — IA · 디자인 토큰 · 접근성 (초안)

> **근거**: [[DART공시통역_통합기획서]] §1.4 페르소나(A~F) · §4 서비스 흐름 · §4.5 상세 분석 화면 · §5.1 프론트 라우트 · §5.3 프론트 스택 · §8.1 티어 · §11 면책.
> **연계**: 소비 API는 [[api_spec]], 데이터/티어 노출은 [[db_schema]]·[[feature_structure]].
> **규칙**: 색·간격·라운드·그림자·모션은 **TailwindCSS 토큰 / shadcn 테마만** 사용, `#hex`·임의 `px` 직접 입력 금지(예외: 토큰 정의 파일 자체) ([[CLAUDE]] §6-4). **호재/악재 배지는 색상 단독 금지 → 색+텍스트/아이콘 병용**, 본문 대비 4.5:1 이상, 모든 인터랙티브 요소 `aria-label`·`:focus-visible`·키보드 경로([[CLAUDE]] §6-5). 면책 문구·신고 경로 동반([[CLAUDE]] §6-6).
>
> 본 문서는 **초안**이다. 토큰 실제 값은 `frontend/src/app/globals.css`(CSS 변수) + `tailwind.config`가 SSOT이며, 본 문서는 의미와 규칙을 정의한다.

---

## 1. 정보 구조 (IA) — 라우트 트리

> §5.1 라우트 + [[CLAUDE]] §3 `src/app/` 구조 기준 (Next.js 15 App Router).

```
/                      랜딩(PUBLIC) — 가치 제안 + "정보 제공 도구, 투자자문 아님" 명시(§11)
├── /signup            가입(PUBLIC) — 이메일/카카오/구글/네이버 + 약관 동의
├── /login             로그인(PUBLIC)
├── /dashboard         (USER) 홈 — 보유 종목 최신 공시·분석 피드
├── /portfolios        (USER) 보유 종목 관리 — 검색·추가·매수가(선택)·CSV 업로드
├── /disclosures
│   └── /[id]          (USER) 공시 상세 — §4.5 헤더/본문/Pro/Premium/공통 CTA
├── /notifications     (USER) 알림 이력 + 설정(채널·빈도·필터·거래시간외)
└── /pricing           (PUBLIC) 요금제 — Free/Pro/Premium 비교 + 업셀
```

### 1.1 페이지 ↔ API 소비 매핑

| 라우트 | 주요 API([[api_spec]]) | 비고 |
|------|------|------|
| `/signup` | `POST /auth/signup`, `GET /auth/oauth/{p}/url` | 동의 항목 함께 전송 |
| `/dashboard` | `GET /disclosures?scope=portfolio`, `GET /users/me` | 피드 + 빈 상태(보유 0) |
| `/portfolios` | `GET/POST/PATCH/DELETE /portfolios`, `GET /stocks/search` | Free 3개 제한 UX |
| `/disclosures/[id]` | `GET /disclosures/{id}`, `GET /disclosures/{id}/analysis`, `POST /analyses/{id}/feedback` | 티어별 필드 차등 |
| `/notifications` | `GET /notifications`, `GET/PUT /notifications/settings` | |
| `/pricing` | `GET /pricing/plans` | MVP 결제 미연동 |

---

## 2. 디자인 토큰 (shadcn/ui 테마 변수)

shadcn 규약대로 **시맨틱 CSS 변수**로 정의하고 컴포넌트는 변수만 참조한다. 원시 색상 직접 사용 금지.

### 2.1 기본 시맨틱 (shadcn 표준)

| 토큰 | 용도 |
|------|------|
| `--background` / `--foreground` | 페이지 배경 / 본문 텍스트 |
| `--card` / `--card-foreground` | 카드 표면 / 카드 텍스트 |
| `--primary` / `--primary-foreground` | 주요 액션(CTA) |
| `--muted` / `--muted-foreground` | 보조 텍스트·비활성 |
| `--border` / `--input` / `--ring` | 경계 / 입력 / 포커스 링 |
| `--destructive` | 파괴적 액션(삭제·탈퇴) — **악재 색과 분리** |

### 2.2 도메인 토큰 — 호재/중립/악재 (★ 핵심 결정)

**한국 증시 색 관행을 따른다: 상승=빨강, 하락=파랑.** 페르소나 A~F가 모두 한국 개인 투자자(§1.4)이므로, 서구 관행(green=good)이 아니라 한국 투자자의 직관(빨강=상승=호재)에 맞춘다.

| 의미 | 토큰 | 색 계열 | 아이콘 | 라벨 |
|------|------|------|------|------|
| 호재(POSITIVE) | `--sentiment-positive` / `-fg` | red 계열 | ▲ (상승) | "호재" |
| 중립(NEUTRAL) | `--sentiment-neutral` / `-fg` | gray 계열 | ― (보합) | "중립" |
| 악재(NEGATIVE) | `--sentiment-negative` / `-fg` | blue 계열 | ▼ (하락) | "악재" |
| 판단 보류 | `--sentiment-withheld` / `-fg` | amber/muted | ⚠ | "판단 보류" |

> **불변 규칙([[CLAUDE]] §6-5)**: 배지·차트·텍스트 어디서도 **색 단독으로 의미 전달 금지** → 항상 `색 + 아이콘 + 텍스트` 3중 표기(색맹·고대비 모드 대응). `--destructive`(삭제)와 `--sentiment-negative`(악재)는 **다른 토큰**으로 분리해 "악재=위험 액션" 오인을 막는다.

### 2.3 간격 · 라운드 · 그림자 · 타이포 · 모션

| 범주 | 토큰 소스 | 규칙 |
|------|------|------|
| 간격 | Tailwind spacing scale(`p-4`, `gap-2`…) | 임의 `px` 금지 |
| 라운드 | `--radius`(shadcn) → `rounded-lg` 등 | 카드/배지 일관 |
| 그림자 | Tailwind `shadow-sm/md` | 임의 그림자 금지 |
| 타이포 | Tailwind `text-sm/base/lg…` + 폰트 변수 | 본문 기본 `text-base` |
| 모션 | Tailwind `transition`, `motion-safe:` | `prefers-reduced-motion` 존중 |

---

## 3. 핵심 컴포넌트 (shadcn 매핑)

| 컴포넌트 | 기반(shadcn) | 용도 |
|------|------|------|
| `SentimentBadge` | `Badge` | 호재/중립/악재/판단보류 — §2.2 3중 표기 강제 |
| `ConfidenceMeter` | `Progress` + 라벨 | 신뢰도 0~1 시각화, 임계 미만 시 "판단 보류" |
| `DisclaimerNotice` | `Alert` | 분석 화면/알림 하단 면책 문구(§11.2) |
| `DisclosureCard` | `Card` | 피드 항목(회사·제목·배지·요약) |
| `TierGate` | `Card` + `Button` | 티어 미달 필드 자리 업셀 CTA(노출 후 마스킹 아님) |
| `PriceReactionChart` | Recharts `LineChart` | 과거 5일 주가 반응(Pro), 색+직접 라벨 |
| `FeedbackPrompt` | `RadioGroup` + `Textarea` | "유용함/부정확함" + 사유(§4.6) |
| `StockSearchCombobox` | `Command` | 종목 자동완성(§4.2) |

### 3.1 공시 상세 화면 구성 (§4.5)

```
[헤더]  회사명 · 종목코드 · 공시 제목(원본 그대로) · SentimentBadge · ConfidenceMeter
[본문]  3줄 요약 · 핵심 정보 · 호재/악재 근거
[Pro]   유사 공시 5건 + PriceReactionChart   ← 미달 시 TierGate
[Premium] 재무 상태 + 업황 분석              ← 미달 시 TierGate
[공통]  DART 원문 링크 · DisclaimerNotice · FeedbackPrompt · "다음 알림 설정" CTA
```

> 원문 인용 필드(회사명·제목·수치·날짜)는 [[api_spec]]가 반환하는 **원본 그대로 렌더**(LLM 변형 금지). 티어 미달 상위 Stage는 API에서 응답 제외 → 화면은 `TierGate`로 대체.

---

## 4. 상태 관리 · 데이터 페칭 (§5.3)

| 관심사 | 도구 | 규칙 |
|------|------|------|
| 서버 상태(공시·분석·종목) | TanStack Query 5 | 쿼리 키 = 리소스+파라미터, 캐시·무효화 |
| 클라이언트 상태(UI/필터/세션) | Zustand 5 | 전역 최소화 |
| 폼 | React Hook Form + Zod | **LLM/외부 입력은 Zod 스키마 파싱 후 사용**([[CLAUDE]] §6-6) |
| 인증 | NextAuth.js v5 | 자체 JWT 보관·갱신, 보호 라우트 가드 |

---

## 5. 접근성 (WCAG 2.1 AA) — [[CLAUDE]] §6-5

| 항목 | 기준 |
|------|------|
| 대비 | 본문 4.5:1, 큰 글자/UI 3:1 이상 — 호재/악재 토큰 모두 충족하도록 값 선정 |
| 색 의존 금지 | 의미는 색+아이콘+텍스트 (§2.2) |
| 키보드 | 모든 인터랙티브 Tab 도달, `Esc`로 닫기, `Enter`로 실행, 포커스 트랩(모달) |
| 포커스 | `:focus-visible` 링(`--ring`) 항상 노출 |
| 스크린리더 | 배지/차트/아이콘 버튼 `aria-label`, 차트 대체 텍스트(수치 요약) |
| 모션 | `prefers-reduced-motion` 시 애니메이션 축소 |

### 5.1 페르소나 배려 (§1.4)

| 페르소나 | 배려 |
|------|------|
| C(입문 투자자) | 전문 용어 툴팁/풀이(예: "전환사채란?"), 쉬운 카피 |
| E(시니어) | "큰 글자" 토글(타이포 스케일 상향), 핵심 3줄 우선 노출 |
| D(소모임 운영자) | 분석 카드 "공유" 기능(바이럴, §12.2) |

---

## 6. 법적/안전 UI 규칙 (§11)

- **투자 권유 표현 금지**: CTA·요약·근거 카피에 "매수/매도 추천", "꼭 사세요", "수익 보장" 사용 금지. "참고하세요/정보 제공"으로 표기([[CLAUDE]] §7).
- **면책 동반**: 모든 분석 화면·알림에 `DisclaimerNotice`(§11.2) + 신고 경로(`FeedbackPrompt`).
- **판단 보류**: `is_withheld=true`면 호재/악재 단정 UI 금지 → "판단 보류" 상태로 렌더.
- **랜딩 명시**: "정보 제공 도구이며 투자 자문이 아님"을 `/`와 가입 동의 화면에 노출.

---

## 7. 디자인 체크 (이 구조 기준)

- [ ] 색·간격·라운드·그림자·모션 전부 토큰/테마 경유(하드코딩 0, [[CLAUDE]] §6-4)
- [ ] 호재/중립/악재/판단보류 = 색+아이콘+텍스트 3중 표기
- [ ] `--destructive`(삭제)와 `--sentiment-negative`(악재) 토큰 분리
- [ ] 본문 대비 4.5:1, `:focus-visible`, 키보드 전 경로, `aria-label`
- [ ] 티어 미달 상위 Stage는 `TierGate`(응답 제외 전제, 마스킹 아님)
- [ ] 모든 분석/알림에 면책 + 신고 경로, 투자 권유 표현 검수
- [ ] 입문자 용어 풀이 · 시니어 큰 글자 옵션

---

## 관련 문서

- [[api_spec]] — 라우트별 소비 엔드포인트 · 티어 필드 차등 · 면책/신고 필드
- [[feature_structure]] — 분석 결과 생성(티어/판단보류) 백엔드 경로
- [[db_schema]] — sentiment/confidence/is_withheld/tier 원천
- [[DART공시통역_통합기획서]] §1.4·§4.5·§5.1·§5.3·§8.1·§11 · [[CLAUDE]] §6-4·§6-5·§6-6
