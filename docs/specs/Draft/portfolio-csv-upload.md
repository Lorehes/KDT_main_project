---
type: spec
status: Draft
created: 2026-06-22
updated: 2026-06-22
---

# 증권사 CSV 업로드로 포트폴리오 일괄 등록 Spec

> 상태: **Draft** (dc-review-frontend 리뷰 결과 → dc-plan 생성)

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
