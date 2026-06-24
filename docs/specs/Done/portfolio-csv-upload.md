---
type: spec
status: Done
created: 2026-06-22
updated: 2026-06-24
---

# 증권사 CSV 업로드로 포트폴리오 일괄 등록 Spec

> 상태: Draft → Approved → **Done** (2026-06-24, 구현+테스트 완료 — Vitest 44/44 GREEN)

## 배경 / 목적

`/portfolios` 페이지에 CSV 업로드 UI가 시각적으로만 존재하며(`onDragOver/onDrop` 핸들러 없음),
드래그하면 브라우저 기본 동작(파일 열기)이 발생한다.

증권사 거래내역 CSV를 업로드해 보유 종목을 자동 추출하면 종목 개별 검색·등록 반복 작업을 제거할 수 있다.
페르소나 C(일반 투자자 — 다수 종목 보유)와 A(장기 투자자 — 거래내역 파일 보유)에게 핵심 온보딩 UX.

- **영향 페르소나**: A(장기 투자자), C(일반 투자자 — 다수 종목)
- **티어**: Free(3종목 제한 내) + Pro(무제한) — 초과 분은 안내 후 선택 등록

## 요구사항

### FE — 드래그앤드롭 + 클릭 업로드

- [ ] 업로드존 `onDragOver`: `e.preventDefault()` + `isDragging` 상태 → 테두리/배경 강조 스타일
- [ ] 업로드존 `onDragLeave`: `isDragging` 해제
- [ ] 업로드존 `onDrop`: 파일 수신 → `.csv` 확장자 검증 → CSV 파싱 → 종목코드 추출
- [ ] 숨김 `<input type="file" accept=".csv">` — 업로드존 클릭 시 파일 다이얼로그 연결
- [ ] 추출된 종목코드 목록을 등록 API로 일괄 전달

### CSV 파싱 전략 (결정 필요)

- [ ] 국내 주요 증권사 포맷 탐지: 키움(잔고), 삼성증권(잔고조회), 미래에셋(보유자산), NH투자(잔고) 최소 지원
- [ ] 공통 패턴: 6자리 숫자 종목코드 컬럼 추출 (`/\b\d{6}\b/`)
- [ ] 파싱 에러 (인식 불가 포맷) → 안내 메시지 표시

### Free 한도 처리

- [ ] 추출 종목이 잔여 한도 초과 시: "X종목 중 Y종목만 등록 가능합니다" + 사용자가 등록할 종목 선택
- [ ] Pro 유저: 전체 등록

### 등록 방식 (결정 필요 — 아래 "권장 구현 방향" 참고)

- [ ] 추출 종목코드 → 포트폴리오 등록 API 호출

## 영향 범위

- **영향 레이어**: frontend + backend (bulk 엔드포인트 선택 시)
- **영향 파일 (FE)**:
  - `frontend/src/app/(app)/portfolios/page.tsx` — CSV 업로드존 핸들러 추가
  - `frontend/src/lib/api/portfolios.ts` — `useCreatePortfolio` 재사용 또는 bulk 훅 추가
- **영향 파일 (BE, bulk 방식 선택 시)**:
  - `backend/.../user/controllers/PortfolioController.java` — `POST /portfolios/bulk` 추가
  - `backend/.../user/services/PortfolioService.java` — `createBulk(userId, List<String>, tier)` 추가
  - `backend/.../user/dto/PortfolioBulkRequest.java` — 신규 DTO (stock_codes: List<String>)
- **DB 변경**: 없음 (기존 portfolios 테이블 재사용)
- **외부 계약**: 없음

## 관련 패턴 / 과거 사례

- `PortfolioService.createPortfolio()` — Free 3종목 제한 로직(`FREE_TIER_LIMIT = 3`) 참고
- `PortfolioRequest` — `@Pattern(regexp = "\\d{6}")` 종목코드 6자리 검증 패턴
- `PortfolioController` 주석: "대량 포트폴리오 목록이 필요하면 페이지네이션 추가" — bulk 설계 시 참고

## 리스크 / 법적 검토

- **개인정보**: CSV에 매수가·수량 등 금융 개인정보 포함 → FE 파싱 후 종목코드만 추출, 원본 CSV 서버 전송 금지
- **포맷 다양성**: 증권사별 컬럼 이름·인코딩(EUC-KR/UTF-8) 상이 → 인코딩 감지 필요 (`TextDecoder` + BOM 확인)
- **중복 등록**: 이미 등록된 종목코드 포함 시 `409 DUPLICATE` 처리 — 스킵 후 결과 안내

## 권장 구현 방향

### 방향 A: FE 파싱 + 순차 단건 POST (MVP 추천)

장점: BE 변경 없음, 빠른 구현  
단점: N번 API 호출(종목 수만큼), Free 한도 검사를 FE에서 예측해야 함  
구현: `useCreatePortfolio` 훅을 `for...of` 루프로 순차 호출 (병렬 시 Free 한도 race condition)

### 방향 B: 새 `POST /portfolios/bulk` 엔드포인트

장점: 단일 API 호출, BE에서 한도·중복·유효성 원자적 처리  
단점: BE 신규 엔드포인트 필요, PortfolioBulkRequest DTO 추가  
응답 예시: `{ added: ["005930", "000660"], skipped: [{ code: "999999", reason: "NOT_FOUND" }], limitReached: true }`

**MVP는 방향 A 추천** — BE 변경 없이 FE만으로 구현 가능.  
종목 수가 많아지면(Pro 유저 대규모 온보딩) 방향 B로 전환.

### FE 파싱 핵심 로직 스케치

```ts
function parseCsvStockCodes(text: string): string[] {
  const lines = text.split(/\r?\n/);
  const codes = new Set<string>();
  for (const line of lines) {
    const matches = line.match(/\b\d{6}\b/g) ?? [];
    for (const m of matches) codes.add(m);
  }
  return [...codes];
}
```

EUC-KR 처리: `FileReader` → `readAsArrayBuffer` → `TextDecoder("euc-kr")` 시도 후 실패 시 UTF-8 폴백.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-24)

### 코드 대조로 확인된 전제 차이 (구현 전 반영 필수)

Spec 작성(2026-06-22) 이후 포트폴리오 화면이 개편되어 전제 3곳이 어긋남:

1. **CSV 업로드존 위치가 다름.** Spec은 `portfolios/page.tsx`에 업로드존이 있다고 기술하나, 실제로 `page.tsx`는 **대시보드로 개편**되었고 업로드존은 없음. CSV 업로드존(정적·핸들러 없음)은 **`portfolios/new/page.tsx:186-193`**에 존재. → 영향 파일은 `page.tsx`가 아니라 `new/page.tsx`.

2. **등록 흐름이 단건 폼 기반.** 현재 등록 경로는 검색 → 선택 → `/portfolios/add?code=...&name=...&market=...`(별도 폼에서 매수가·수량 입력) → `POST /portfolios` 단건. CSV로 추출한 종목은 **매수가·수량이 없음** — Spec 결정(개인정보 보호 위해 종목코드만 추출)과 일치하므로, CSV 등록 종목은 `avg_buy_price/quantity = null`로 저장(현 `PortfolioService.createPortfolio`가 null 허용, `avg_buy_price_enc`/`quantity_enc` nullable). 이후 사용자가 `/portfolios/add` 또는 수정으로 보완.

3. **커버리지 밖 종목 다수 예상.** CSV에는 전종목 보유분이 들어올 수 있으나, `createPortfolio`는 stocks 마스터(코스피200+코스닥150) 미등재 종목코드에 **400 반환**. CSV 추출 종목 중 상당수가 커버리지 밖일 수 있음 → "등록 불가(미지원)" 스킵 처리 + 결과 안내 필수.

### 방향 결정 (Spec "결정 필요" 해소)

- **방향 A(FE 파싱 + 순차 단건 POST) 채택 권장** — BE 변경 0, MVP 범위에 적합. 단 `useCreatePortfolio`를 루프로 호출하면 호출마다 `["portfolios"]` invalidate가 발생(N회 리페치)하므로, **`apiClient`를 직접 순차 호출하고 루프 종료 후 `invalidateQueries` 1회**로 처리(작업 카드 4). Free 한도·중복(409)·미지원(400)은 순차라 race 없이 개별 집계.
- 방향 B(`POST /portfolios/bulk`)는 대규모 온보딩(Pro) 시 후속 과제로 분리. 현 단계 BE 신규 엔드포인트는 과투자.

### 아키텍처 분해
- 영향 레이어: **frontend 단독**(portfolios) — 방향 A 채택 시 backend 변경 없음
- 신규: `lib/csv/parsePortfolioCsv.ts`(파싱 유틸), 추출 종목 확인 UI(모달 또는 인라인 패널)
- 수정: `portfolios/new/page.tsx`(업로드존 핸들러 연결)
- 재사용: `useCreatePortfolio` 또는 `apiClient`(POST /portfolios), `useTierCheck`(Free/Pro 한도), `usePortfolios`(잔여 한도 계산)

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `lib/csv/parsePortfolioCsv.ts` — `readAsArrayBuffer` + `TextDecoder("euc-kr")` 시도→UTF-8 폴백(BOM 확인), `/\b\d{6}\b/g` 추출, Set dedup | frontend/lib | FE | 중 | - |
| 2 | `new/page.tsx` 업로드존에 `onDragOver`(preventDefault+isDragging)·`onDragLeave`·`onDrop` + 숨김 `<input type=file accept=.csv>` + 클릭 연결 | frontend/portfolios | FE | 중 | #1 |
| 3 | 추출 종목 확인 UI — 잔여 한도 표시, Free 초과 시 선택(체크박스), 미지원(커버리지 밖)·중복 종목 사전 표시 | frontend/portfolios | FE | 상 | #1,#2 |
| 4 | 일괄 등록 — `apiClient` 순차 POST + 결과 집계(added/skipped:중복·미지원·한도초과) + 루프 종료 후 `invalidateQueries(["portfolios"])` 1회 + 결과 토스트/요약 | frontend/portfolios | FE | 중 | #3 |
| 5 | `parsePortfolioCsv` Vitest 단위 테스트 — EUC-KR/UTF-8 인코딩, 6자리 추출, 중복 제거, 빈/비정상 CSV 경계 | frontend/test | FE | 하 | #1 |

### DB / 마이그레이션 영향
- **없음.** 기존 `portfolios` 테이블 재사용. `avg_buy_price_enc`/`quantity_enc` 이미 nullable(V3) — CSV 등록 종목 null 저장 가능. Flyway 신규 파일 불필요.

### 외부 계약 영향
- **없음.** DART/KRX/카카오/LLM 무관. 자체 API도 방향 A에서는 기존 `POST /api/v1/portfolios` 단건 재사용(계약 불변). 방향 B 채택 시에만 `POST /portfolios/bulk` 신규.

### 리스크 & 법적 검토
- **금융 개인정보(통합기획서 §11.1)**: CSV에 매수가·수량 포함 가능 — **FE 파싱 후 종목코드만 추출, 원본 CSV·매수가·수량 서버 전송 절대 금지**. 파싱 값 `console.log` 금지(기존 `new/page.tsx`·`add/page.tsx` 주석 규칙 동일). CSV 등록 종목 매수가는 null로 두고 사용자가 직접 입력하므로 PII 추출 위험 자체를 회피.
- **커버리지 밖 종목 처리**: 미지원 종목코드 400을 에러로 노출하지 말고 "등록 불가(미지원)"로 집계해 사용자 혼란 방지.
- **인코딩 오판**: EUC-KR을 UTF-8로 디코드 시 한글 깨짐 — 단 종목코드는 ASCII 6자리라 추출 로직 자체는 인코딩 영향 적음. 화면 표기용 종목명은 마스터(`useStockSearch`/`corp_name`)에서 가져오므로 CSV 한글 의존 불필요.
- **자본시장법**: 해당 없음(보유 종목 등록 기능, 투자 권유·표현 무관).
- **부분 실패 UX**: 순차 등록 중 일부 실패 시 "X종목 중 Y 등록, Z 스킵(중복/미지원/한도)" 요약 필수 — 침묵 실패 금지.

### 예상 wave 수
- **1 wave** (방향 A, FE 단독). 카드 1~5를 한 PR로 묶음. 방향 B로 전환 시 BE wave(엔드포인트+DTO+서비스+통합테스트) 1개 추가 → 총 2 wave.
