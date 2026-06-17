---
type: spec
status: Draft
created: 2026-06-17
updated: 2026-06-17
---

# 랜딩 히어로 목업 강화 Spec

> 상태: **Draft** (dc-plan 생성)

## 배경 / 목적

- **문제**: 현재 히어로 우측 영역은 `opacity-40` 처리된 배지 3개 + "[ 대시보드 미리보기 ]" 텍스트만 표시되는 빈 placeholder. 데모 가독성이 낮아 서비스 가치 전달 실패 → 전환율 저하 우려.
- **목표**: 실제 공시 알림이 실시간으로 오는 것처럼 보이는 시뮬레이션 목업을 코드로 구현해 LP 전환율 개선.
- **페르소나 연결**: A(온라인 자기주도 투자자), C(시니어 투자자 — 큰 텍스트·명확한 배지), E(입문 투자자 — 쉬운 용어).
- **BM 티어**: 마케팅 랜딩(무료 전환 유입), Free 이상 모든 티어에 영향.
- **법적 제약**: 목업 데이터는 허구(fictional) 명시 필요 — 실제 투자 결과로 오인 방지 (통합기획서 §11.1).

---

## 요구사항

- [ ] 히어로 우측 placeholder를 풀사이즈 시뮬레이션 목업으로 교체 (높이 380px 이상, 전체 공간 활용)
- [ ] 목업 내 공시 카드 2–3개 표시 — 회사명·공시 제목·호재/악재/중립 배지·요약 1줄 포함
- [ ] 배지는 기존 `SentimentBadge` 컴포넌트 또는 동일 토큰·3중 표기 패턴 재사용
- [ ] 진입 애니메이션: 카드 순차적 fade-in + slide-up (staggered, 0.15s 간격)
- [ ] "허구 데이터 — 데모용" 안내 뱃지 또는 문구 삽입 (자본시장법 §11.1 면책)
- [ ] 반응형: Mobile에서는 기존과 동일하게 hidden (`md:flex` 유지)
- [ ] 접근성: `role="img"` + `aria-label="서비스 데모 미리보기"` 유지, 카드 내 배지는 aria-hidden 처리 (장식용)
- [ ] Tailwind 토큰·shadcn 테마만 사용, `#hex`·임의 `px` 하드코딩 금지

---

## 영향 범위

- **영향 레이어**: frontend 단독
- **영향 파일**:
  - `frontend/src/app/page.tsx` — 히어로 섹션 우측 div 교체
  - `frontend/src/components/domain/SentimentBadge.tsx` — 재사용만, 수정 없음
  - *(선택)* `frontend/src/components/domain/HeroMockupCard.tsx` — 신규 도메인 컴포넌트 분리 가능
- **DB 변경**: 없음 (정적 시뮬레이션 데이터)
- **외부 계약 변경**: 없음
- **Flyway 마이그레이션**: 불필요

---

## 관련 패턴 / 과거 사례

- `frontend/src/components/domain/SentimentBadge.tsx` — 색+아이콘+텍스트 3중 표기 패턴 SSOT
- `frontend/src/components/domain/DisclosureCard.tsx` — 공시 카드 레이아웃 참고 (목업은 동일 외형)
- `frontend/src/app/page.tsx:112–124` — 현재 placeholder (교체 대상)
- `design_structure.md §2.2` — `--sentiment-positive/neutral/negative` 토큰 확정값

---

## 시뮬레이션 데이터 (허구)

```typescript
// 자본시장법 §11.1 — 허구 표기 필수
const MOCK_DISCLOSURES = [
  {
    company: "삼성전자",
    ticker: "005930",
    title: "주요사항보고서(유상증자결정)",
    sentiment: "negative",
    summary: "보통주 5% 규모 유상증자 결정으로 단기 희석 우려.",
    time: "방금 전",
  },
  {
    company: "SK하이닉스",
    ticker: "000660",
    title: "실적발표(2025 3Q)",
    sentiment: "positive",
    summary: "HBM3E 출하 증가로 영업이익 전분기 대비 42% 상승.",
    time: "3분 전",
  },
  {
    company: "카카오",
    ticker: "035720",
    title: "임원ㆍ주요주주특정증권등소유상황보고서",
    sentiment: "neutral",
    summary: "최대주주 지분 변동 없음, 내부 거래 예정 없음.",
    time: "8분 전",
  },
] as const;
```

---

## 애니메이션 패턴

Tailwind v4 CSS 변수·keyframe 활용. `animation-delay`는 인라인 스타일로 stagger.

```tsx
// globals.css에 keyframe 추가 (또는 tailwind.config의 extend.keyframes)
// @keyframes slideUpFade { from { opacity: 0; transform: translateY(16px); } to { opacity: 1; transform: translateY(0); } }

// 컴포넌트 내:
<div
  className="animate-[slideUpFade_0.4s_ease-out_both]"
  style={{ animationDelay: `${index * 0.15}s` }}
>
```

---

## 리스크 / 법적 검토

- **자본시장법 §11.1**: 목업 데이터(회사명·수치)가 실제 공시 결과로 오인될 경우 투자 권유 오해 소지. → 카드 상단 또는 목업 컨테이너 하단에 "데모 시뮬레이션 — 실제 데이터 아님" 명시 **필수**.
- **유명 기업 허구 언급**: 삼성전자·SK하이닉스 등 실존 기업명 사용 시 허구 표기로 충분하나, 더 안전한 대안은 익명 "A사/B사" 사용. → 구현 시 팀 검토 권장 (Approved 단계에서 결정).
- **접근성**: 배지 색상 단독 사용 금지 — `SentimentBadge` 재사용으로 자동 충족.
- **SSR 애니메이션**: `animation-delay` 인라인 스타일은 서버 렌더 OK (CSS 스타일 속성, JS 불필요).

---

## 권장 구현 방향

### 접근법 A — page.tsx 내 인라인 구현 (권장)
- placeholder div를 직접 확장, `HeroMockupCard` 서브컴포넌트는 page.tsx 하단 로컬 함수로 정의
- **장점**: 파일 1개만 변경, 마케팅 LP는 자주 바뀌지 않아 분리 오버헤드 불필요
- **단점**: page.tsx가 약간 길어짐 (~60줄 추가)

### 접근법 B — `HeroMockupCard` 도메인 컴포넌트 분리
- `frontend/src/components/domain/HeroMockupCard.tsx` 신규 생성
- **장점**: 재사용 가능, 테스트 격리
- **단점**: LP 전용 컴포넌트로 재사용 가능성 낮음, 컴포넌트 수 증가

**권장**: 접근법 A (인라인). 공유 컴포넌트가 아닌 LP 전용 비주얼이므로 분리 불필요.

---

### 목업 레이아웃 스케치 (ASCII)

```
┌─────────────────────────────────────────┐  ← rounded-2xl, border border-white/15, bg-white/6
│  📋 데모 시뮬레이션 — 실제 데이터 아님   │  ← 상단 안내 배지 (text-[11px], white/50)
├─────────────────────────────────────────┤
│  ● 삼성전자 005930                3분전 │  ← 카드 1 (animate-in, delay 0s)
│  주요사항보고서(유상증자결정)            │
│  [▼ 악재]  보통주 5% 희석 우려.         │
├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤
│  ● SK하이닉스 000660              5분전 │  ← 카드 2 (delay 0.15s)
│  실적발표(2025 3Q)                      │
│  [▲ 호재]  영업이익 +42%.               │
├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤
│  ● 카카오   035720               12분전 │  ← 카드 3 (delay 0.3s)
│  임원소유상황보고서                      │
│  [― 중립]  지분 변동 없음.               │
└─────────────────────────────────────────┘
```

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
