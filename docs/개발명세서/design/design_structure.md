---
type: doc
status: active
created: 2026-05-30
updated: 2026-06-11
---

# 디자인 구조 — IA · 토큰 · 컴포넌트 · 플로우 (확정)

> **SSOT**: 본 문서는 `docs/개발명세서/design/` 폴더의 화면 명세서·플로우맵 HTML을 기준으로 확정됐다.
> - 웹 화면 명세서: `공시레이더 - 웹 화면 명세서.html`
> - 모바일 화면 명세서: `공시레이더 - 모바일 화면 명세서.html`
> - 웹 플로우맵: `공시레이더 - 웹 플로우맵.html`
> - 모바일 플로우맵: `공시레이더 - 모바일 플로우맵.html`
>
> **규칙**: 색·간격·라운드·그림자·모션은 **TailwindCSS 토큰 / shadcn 테마만** 사용 ([[CLAUDE]] §6-4).
> **호재/악재 배지는 색상 단독 금지** → 색+아이콘+텍스트 3중 표기 ([[CLAUDE]] §6-5).
> 토큰 실제 값의 SSOT는 `frontend/src/app/globals.css`.

---

## 1. 정보 구조 (IA) — 라우트 트리

```
/                      D1/01  랜딩(PUBLIC) — 가치 제안 + 면책 고지
├── /signup                   가입(PUBLIC) — 이메일/카카오/구글 + 4단계 온보딩
│   ├── /verify         D6/10  이메일 OTP 인증 (STEP 1)
│   ├── /terms          D7/11  약관 동의 (STEP 2)
│   ├── /phone          D8/12  휴대폰 인증 (STEP 3, 선택)
│   ├── /profile        D9/13  프로필 설정 (STEP 4)
│   └── /done          D10/14  가입 완료
├── /login              D5/02  로그인(PUBLIC)
├── /dashboard          D2/03  홈 — 보유 종목 최신 공시·분석 피드 (앱 허브)
├── /portfolios         D12/05 보유 종목 관리 — 검색·추가·매수가
│   └── /[id]          D13/22  종목 상세 · 매수정보 입력
├── /disclosures        D15/23 공시 피드 리스트
│   └── /[id]                  공시 상세 — 티어별 3단계
│       ├── (Free)      D3/04  기본 해석 (Stage 1~2)
│       ├── (Pro)       D3/16  유사 공시 + 주가 반응 (Stage 3~4)
│       └── (Premium)  D17/17  재무·업황 분석 (Stage 5)
├── /notifications      D14/06 알림 설정
│   └── /center        D24/26  알림 센터 (전체 이력)
└── /pricing            D4/07  요금제 — Free/Pro/Premium 비교 + 업셀
```

### 1.1 존(Zone) 분류 — 플로우맵 색상 기준

| Zone | 색상 | 화면 범위 |
|------|------|-----------|
| A — 진입/마케팅 | `#1E6FE0` | 랜딩, 요금제 (비로그인) |
| B — 가입/온보딩 | `#4338CA` | 로그인, 가입 4단계 |
| C — 종목 관리 | `#0F766E` | 포트폴리오 등록·상세 |
| D — 핵심/대시보드 | `#0F2A47` | 공시 피드·상세 (앱 허브) |
| E — 알림 | `#6D28D9` | 알림 설정·팝오버·센터 |
| F — 결제/구독 | `#B45309` | 업셀 모달·Checkout |
| G — 계정/기타 | `#475569` | 마이페이지·드로어·설정 |

### 1.2 페이지 ↔ API 소비 매핑

| 라우트 | 주요 API ([[api_spec]]) | 비고 |
|--------|------------------------|------|
| `/signup` | `POST /auth/signup`, `GET /auth/oauth/{p}/url` | 동의 항목 함께 전송 |
| `/dashboard` | `GET /disclosures?scope=portfolio`, `GET /users/me` | 피드 + 빈 상태 |
| `/portfolios` | `GET/POST/PATCH/DELETE /portfolios`, `GET /stocks/search` | Free 3개 제한 UX |
| `/disclosures/[id]` | `GET /disclosures/{id}`, `GET /disclosures/{id}/analysis` | 티어별 필드 차등 |
| `/notifications` | `GET /notifications`, `GET/PUT /notifications/settings` | |
| `/pricing` | `GET /pricing/plans` | MVP 결제 미연동 |

---

## 2. 디자인 토큰 (확정값)

> 원본 출처: 화면 명세서 HTML `:root` CSS 변수.
> `frontend/src/app/globals.css`에 OKLCH로 변환 적용됨.

### 2.1 컬러 팔레트

| 용도 | CSS 변수 | HEX | 설명 |
|------|----------|-----|------|
| **브랜드 Navy** | `--brand-navy` | `#0F2A47` | 메인 텍스트·배경·Topbar |
| **브랜드 Blue** | `--brand-blue` | `#1E6FE0` | 주요 CTA·인터랙션 (`--primary`로 오버라이드) |
| **브랜드 Sky** | `--brand-sky` | `#4FA3F7` | 밝은 강조·서브 배지 |
| 페이지 배경 | `--background` | `#FFFFFF` | |
| 보조 배경 | `--surface` (`--muted`) | `#F5F7FA` | 카드 바깥·테이블 hover |
| 구분선 | `--line` (`--border`) | `#D9DEE5` | |
| 본문 텍스트 | `--ink` (`--foreground`) | `#1A1F26` | |
| 보조 텍스트 | `--grey` (`--muted-foreground`) | `#667085` | |

### 2.2 시장 신호 토큰 (★ 핵심 — 한국 증시 관행)

**상승=빨강, 하락=파랑** — 한국 투자자 직관 기준. 서구 관행(green=good) 사용 금지.

| 의미 | 토큰 | HEX | BG HEX | 아이콘 | 라벨 |
|------|------|-----|--------|--------|------|
| 호재 POSITIVE | `--sentiment-positive` | `#E23E3E` | `#FBE6E6` | ▲ | "호재" |
| 중립 NEUTRAL | `--sentiment-neutral` | `#667085` | `#EDF0F4` | ― | "중립" |
| 악재 NEGATIVE | `--sentiment-negative` | `#1551C4` | `#E7EEFB` | ▼ | "악재" |
| 판단 보류 WITHHELD | `--sentiment-withheld` | `#5B43C0` | `#ECE9FB` | ⚠ | "판단 보류" |

> **불변 규칙**: 배지·차트·텍스트 어디서도 **색 단독 사용 금지** → 항상 `색 + 아이콘 + 텍스트` 3중 표기.
> `--destructive`(삭제·탈퇴)와 `--sentiment-negative`(악재)는 **다른 토큰**으로 분리.

### 2.3 라운드 토큰

| 용도 | 값 |
|------|----|
| 카드 (`--r-card`) | 16px |
| 배지 (`--r-badge`) | 8px |
| 버튼 (`--r-btn`) | 12px |
| 아이콘 박스 | 11px (웹) / 9px (모바일) |
| 통계 카드 | 14px |
| 모달 | 20px |
| 입력 필드 | 12px |

### 2.4 그림자 토큰

| 용도 | 값 |
|------|----|
| 카드 (`--sh-card`) | `0 2px 8px rgba(15,42,71,.08)` |
| 알림 팝오버 | `0 24px 60px rgba(15,42,71,.28)` |
| 공유 카드 | `0 10px 30px rgba(15,42,71,.3)` |

### 2.5 타이포그래피 스케일

**웹 (Desktop)**

| 레벨 | size | weight | letter-spacing | 용도 |
|------|------|--------|----------------|------|
| Display | 46–50px | 800 | -0.03em | 랜딩 히어로 H1 |
| H1 | 26px | 800 | -0.025em | 페이지 제목 |
| H2 | 19px | 700 | -0.02em | 섹션 제목 |
| Body | 15–16px | 400 | normal | 본문 |
| Sub | 15px | 400 | normal | 부가 설명 |
| Cap | 13px | 400 | normal | 캡션·메타 |
| Eyebrow | 12px | 800 | 0.08em | 라벨 (대문자) |

**모바일**

| 레벨 | size | weight | 용도 |
|------|------|--------|------|
| Display | 27px | 800 | 큰 제목 |
| H1 | 22px | 800 | 화면 제목 |
| H2 | 18px | 700 | 섹션 제목 |
| Body | 15px | 400 | 본문 |
| Sub | 13.5px | 400 | 부가 설명 |
| Cap | 12px | 400 | 캡션 |
| Eyebrow | 11px | 800 | 라벨 |

---

## 3. 레이아웃 패턴

### 3.1 웹 (Desktop — 1440px 기준)

```
┌──────────────────────────────── Topbar (64px) ────────────────────────────────┐
│  [로고 36px]  [검색바 max-w-440px]                  [알림벨 42px] [아바타 38px]  │
├──────────────┬─────────────────────────────────────────────────────────────────┤
│              │                                                                 │
│  Sidebar     │  Main Content (flex:1, padding: 28px 32px, bg: surface)        │
│  (240px)     │                                                                 │
│  padding:    │  - 통계 카드 그리드: 4열, gap 16px                              │
│  20px 14px   │  - 공시 테이블: 전체 너비, 행 높이 auto                         │
│              │  - 상세 2단: 1fr + 380px sidebar                                │
│  [섹션 라벨] │                                                                 │
│  [사이드 항목│                                                                 │
│   12px 14px] │                                                                 │
│              │                                                                 │
│  [업셀 패널  │                                                                 │
│   Navy 배경] │                                                                 │
└──────────────┴─────────────────────────────────────────────────────────────────┘
```

- **Sidebar 항목**: 높이 44px, 라운드 11px, 활성: Blue BG + blue 텍스트
- **업셀 패널**: Navy 배경, 라운드 14px, padding 16px

### 3.2 모바일 (390px 기준)

```
┌──── 상태바 (44px, Navy) ────┐
├──── AppBar (56px, Navy) ────┤  [햄버거] [브랜드] [알림벨]
├─────────────────────────────┤
│                             │
│  콘텐츠 영역                │  padding: 20px 가로
│  (scroll)                   │
│                             │
├──── BottomTabBar ───────────┤  4탭: 대시보드 / 종목 / 알림 / 요금제
└─────────────────────────────┘
```

### 3.3 인증 화면 (2단 레이아웃 — 웹만)

```
┌──────────────────┬──────────────────┐
│  Left (Navy bg)  │  Right (White)   │
│  - 브랜드 메시지  │  - 폼 / 스텝퍼   │
│  - 서비스 소개    │  max-w: 460px    │
│  64px 56px pad   │  64px 72px pad   │
└──────────────────┴──────────────────┘
```

---

## 4. 핵심 컴포넌트 스펙

### 4.1 버튼

| 종류 | 높이 | 폰트 | 라운드 | 색상 |
|------|------|------|--------|------|
| Primary | 48px (모바일 46px) | 15px/700 | 12px | bg: `--blue`, color: white |
| Ghost | 48px | 15px/700 | 12px | bg: white, border 1.5px `--line` |
| Small | 40px | 13.5px/700 | 10px | Primary 색상 기준 |
| Kakao | 48px | 15px/700 | 12px | bg: `#FEE500`, color: `#3C1E1E` |

### 4.2 배지 (SentimentBadge)

- **크기**: padding 5px 10px, 라운드 8px, 폰트 12px/800
- **구성**: `색 + 점(6px) + 텍스트` — 색 단독 금지
- **알림 배지 (소형)**: padding 2px 7px, 라운드 5px, 폰트 10.5px/800

### 4.3 카드

| 속성 | 웹 | 모바일 |
|------|-----|--------|
| 배경 | white | white |
| 테두리 | 1px `--line` | 1px `--line` |
| 라운드 | 16px | 16px |
| 패딩 | 20px | 16px |
| 그림자 | `--sh-card` | `--sh-card` |

### 4.4 공시 상세 화면 구성 (§4.5)

```
[헤더]  회사명 · 종목코드 · 공시 제목(원본) · SentimentBadge · ConfidenceMeter
[본문]  3줄 요약 · 핵심 정보 · 호재/악재 근거 (Stage 1~2, Free)
[Pro]   유사 공시 5건 + PriceReactionChart   ← Free 시 TierGate (Stage 3~4)
[Premium] 재무 상태 + 업황 분석              ← Pro 이하 시 TierGate (Stage 5)
[공통]  DART 원문 링크 · DisclaimerNotice · FeedbackPrompt
```

> 원문 인용 필드(회사명·제목·수치·날짜)는 **원본 그대로 렌더** — LLM 변형 금지.

### 4.5 Progressive Disclosure (모바일 공시 카드)

- **닫힘**: 요약 정보만, chevron ↓
- **열림**: 파란 테두리 + Blue BG 그림자, 상세 영역 노출, chevron ↑
- 탭: 03 대시보드 공시 피드, 04 공시 상세 기본

### 4.6 Topbar 알림벨

- 기본: 42px, 라운드 11px, bg: surface
- 미읽음 dot: 8px, Navy bg, 위치 top-right, border 1.5px white
- 활성(열림): Blue BG + 1.5px blue 테두리

### 4.7 shadcn 컴포넌트 매핑

| 컴포넌트 | 기반 | 용도 |
|----------|------|------|
| `SentimentBadge` | `Badge` | 호재/중립/악재/판단보류 3중 표기 강제 |
| `ConfidenceMeter` | `Progress` + 라벨 | 신뢰도 바, 120–160px, 높이 8px |
| `DisclaimerNotice` | `Alert` | 분석·알림 하단 면책 문구 |
| `DisclosureCard` | `Card` | 피드 항목(회사·제목·배지·요약) |
| `TierGate` | `Card` + `Button` | 티어 미달 업셀 CTA (노출 후 마스킹 아님) |
| `PriceReactionChart` | Recharts `BarChart` | 과거 주가 반응 (Pro), 색+라벨 |
| `FeedbackPrompt` | `RadioGroup` + `Textarea` | 유용함/부정확함 + 사유 |
| `StockSearchCombobox` | `Command` | 종목 자동완성 |

---

## 5. 화면 플로우 (확정)

### 5.1 주요 사용자 경로

```
[비로그인 진입]
랜딩(D1/01)
├─ "무료로 시작" ─→ 로그인/가입(D5/02)
│                    ├─ 신규 ─→ 이메일OTP(D6/10) → 약관(D7/11) → 휴대폰(D8/12) → 프로필(D9/13) → 완료(D10/14)
│                    │           완료 후 ─→ 대시보드(D2/03)
│                    └─ 기존 ─→ 대시보드(D2/03)
├─ "요금제" ──────→ 요금제(D4/07)
└─ "대시보드 둘러보기" → 대시보드(D2/03)  [세션 없으면 /login 리다이렉트]

[로그인 후 — 앱 허브: D2/03]
대시보드
├─ 공시 카드 클릭 ─→ 공시 상세 기본(D3/04)
│                     ├─ Pro 콘텐츠 클릭 ─→ Pro 상세(D3/16) 또는 업셀 모달(D21/18)
│                     └─ Premium 잠금 ───→ Premium 상세(D17/17) 또는 업셀 모달
├─ "전체 보기" ────→ 공시 피드(D15/23)
├─ 알림벨 ─────────→ 알림 팝오버(D23/25) → 알림 센터(D24/26)
├─ "+ 종목" ───────→ 종목 관리(D12/05)
└─ 아바타 ─────────→ 계정 메뉴(D26/09드로어)
                      ├─ 마이페이지 ─→ D16/24
                      ├─ 알림 설정 ──→ D14/06
                      └─ 요금제 ─────→ D4/07

[결제 흐름]
업셀 모달(D21/18) → 결제(D19/19) → 대시보드(D2/03)
요금제(D4/07) "무료체험" → 결제(D19/19) → 대시보드
```

### 5.2 티어별 진입 제한

| 기능 | Free | Pro | Premium |
|------|:----:|:---:|:-------:|
| 공시 기본 해석 | O | O | O |
| 유사 공시 + 주가 반응 | X | O | O |
| 공시 히스토리 3개월 | X | O | O |
| 재무·업황 분석 | X | X | O |
| 종목 등록 수 | 3개 | 무제한 | 무제한 |
| 알림 발송 | 일 5건 | 무제한 | 무제한 |

### 5.3 분기 조건

| 상황 | 조건 | 처리 |
|------|------|------|
| 비인증 접근 | `dr_session` 쿠키 없음 | `/login?redirect={path}` |
| 신규 OAuth 가입 | `requires_renewal=true` | `/signup/terms` 강제 이동 |
| 세션 만료 | 401 응답 | 토큰 갱신 → 실패 시 로그아웃 |
| Free 3개 초과 | portfolio count ≥ 3 | 추가 버튼 비활성 + 업셀 |
| 티어 미달 콘텐츠 | API 미반환 | `TierGate` 컴포넌트 노출 |

---

## 6. 접근성 (WCAG 2.1 AA)

| 항목 | 기준 |
|------|------|
| 대비 | 본문 4.5:1, 큰 텍스트/UI 3:1 이상 |
| 색 의존 금지 | 의미는 색+아이콘+텍스트 3중 표기 (§2.2) |
| 키보드 | 모든 인터랙티브 Tab 도달, `Esc` 닫기, `Enter` 실행, 포커스 트랩(모달) |
| 포커스 | `:focus-visible` 링(`--ring`) 항상 노출 |
| 스크린리더 | 배지/차트/아이콘 `aria-label`, 차트 대체 텍스트 |
| 모션 | `prefers-reduced-motion` 시 애니메이션 축소 |
| 시니어(페르소나 E) | 큰 글자 토글, 핵심 3줄 우선 노출 |
| 입문자(페르소나 C) | 전문 용어 툴팁, 쉬운 카피 |

---

## 7. 법적·안전 UI 규칙

- **투자 권유 표현 금지**: "매수/매도 추천", "꼭 사세요", "수익 보장" 금지 ([[CLAUDE]] §7)
- **면책 동반**: 모든 분석 화면·알림에 `DisclaimerNotice` + `FeedbackPrompt` (신고 경로)
- **판단 보류**: `is_withheld=true` 시 호재/악재 단정 UI 금지 → "판단 보류" 렌더
- **랜딩 명시**: "정보 제공 도구이며 투자 자문이 아님"을 `/`와 가입 동의 화면에 노출

---

## 8. 디자인 체크리스트

- [ ] 색·간격·라운드·그림자·모션 전부 토큰 경유 (하드코딩 0)
- [ ] 호재/중립/악재/판단보류 = 색+아이콘+텍스트 3중 표기
- [ ] `--destructive`(삭제)와 `--sentiment-negative`(악재) 토큰 분리
- [ ] 본문 대비 4.5:1, `:focus-visible`, 키보드 전 경로, `aria-label`
- [ ] 웹: Topbar(64px) + Sidebar(240px) + Main 셸 레이아웃
- [ ] 모바일: 상태바(44px) + AppBar(56px) + BottomTabBar 셸
- [ ] 티어 미달 콘텐츠 = `TierGate` (응답 제외 전제, 마스킹 아님)
- [ ] 모든 분석·알림에 면책 + 신고 경로, 투자 권유 표현 검수
- [ ] 가입 2단 레이아웃 (Left Navy / Right White) 준수

---

## 관련 문서

- [[api_spec]] — 라우트별 소비 엔드포인트·티어 필드 차등
- [[feature_structure]] — 분석 결과 생성(티어/판단보류) 백엔드 경로
- [[db_schema]] — sentiment/confidence/is_withheld/tier 원천
- [[통합기획서]] §1.4·§4.5·§5.1·§5.3·§8.1·§11
- [[CLAUDE]] §6-4·§6-5·§6-6·§7
