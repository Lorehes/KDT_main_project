---
type: issue
status: Open
created: 2026-06-22
updated: 2026-06-22
source: dc-review-frontend (portfolios 리팩터링 후 UI 리뷰)
priority: P2
---

# /portfolios/new 증권사 거래내역 CSV 업로드 미구현

> **상태**: Open — 현재 UI 껍데기만 존재. 통합기획서 §4.2 · §793 명시 1순위 기능.

## 현상

`/portfolios/new` 페이지의 CSV 업로드 존은 디자인만 완성되어 있고, 실제 인터랙션이 없음:

```tsx
// frontend/src/app/(app)/portfolios/new/page.tsx:163–171
<div className="flex flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed ...">
  <Upload className="size-5 ..." />
  <p>증권사 거래내역 CSV 업로드</p>
  <p>파일을 끌어다 놓으면 보유 종목을 자동 추출</p>
</div>
```

- `<input type="file">` 없음
- 드래그앤드롭 이벤트 핸들러 없음
- 파싱·검증·저장 로직 없음
- BE 업로드 엔드포인트 없음 (api_spec.md 확인 필요)

## 배경 (통합기획서 §4.2 · §793)

```
종목 등록 흐름 §4.2:
  거래내역 CSV 업로드
  → 증권사 거래내역 다운로드 가이드
  → CSV 업로드 → 자동 추출 → 확인 → 저장

우선순위 §793: 거래내역 CSV 업로드 — 1순위
```

개인 투자자가 증권사 HTS/MTS에서 내보낸 거래내역 파일을 업로드하면 보유 종목·평균 매수가·수량을 자동으로 추출하는 편의 기능.

## 영향 범위

### Frontend
- `frontend/src/app/(app)/portfolios/new/page.tsx` — 업로드 UI
- `frontend/src/lib/api/portfolios.ts` — `useImportPortfolios` 훅 신규 필요
- 신규: `frontend/src/components/domain/CsvUploadZone.tsx` (또는 인라인)

### Backend
- `backend/.../portfolios/` — POST `/api/v1/portfolios/import` 엔드포인트 신규 필요
- CSV 파싱 로직 (증권사별 포맷 대응)
- 보유 정보(평단·수량) AES-256-GCM 암호화 저장 (CLAUDE.md §6-3 필수)

## 기술 검토 필요 사항

### 1. 증권사별 CSV 포맷 차이
주요 증권사(키움·미래에셋·삼성·한국투자·NH투자 등)의 거래내역 CSV 컬럼명과 인코딩(EUC-KR 가능)이 다름. 통일된 파싱 전략 필요.

### 2. 파싱 위치 — FE vs BE
| 방식 | 장점 | 단점 |
|------|------|------|
| **FE 파싱** (JavaScript) | 서버 전송 전 미리보기·검증 가능, 민감 데이터 최소 전송 | 브라우저 EUC-KR 지원 제한 (`TextDecoder` 필요), 증권사별 분기 복잡 |
| **BE 파싱** (Java) | Apache Commons CSV 등 라이브러리 활용, EUC-KR 완전 지원 | 원본 파일 서버 전송 (평단·수량 노출 경로 증가) |

**권장**: FE에서 종목 코드·수량만 1차 추출 → 확인 UI → BE에 구조화 데이터 전송 (원본 파일 미전송).

### 3. 업로드 플로우
```
파일 선택/드롭
  → FE CSV 파싱 (종목코드·수량·평단 추출)
  → 확인 UI (파싱 결과 테이블 표시, 사용자 수정 가능)
  → POST /api/v1/portfolios/bulk (구조화 배열)
  → 성공 → /portfolios 이동
```

### 4. 에러 처리
- 지원하지 않는 증권사 포맷 → "지원 증권사 목록 안내" 표시
- 종목 코드 미인식 (코스피200+코스닥150 외) → "추가 등록 요청" 안내 (기존 "찾는 종목이 없나요?" 박스와 연계)
- Free 3종목 제한 초과 → 업로드 전 경고

## 다음 단계

이 이슈를 `/dc-plan` → `/dc-tech-review` → `/dc-implement` 파이프라인으로 진행하려면 먼저:

- [ ] 지원 대상 증권사 범위 확정 (최소 MVP: 키움·미래에셋 2개)
- [ ] `docs/개발명세서/api_spec.md` 에 `POST /api/v1/portfolios/bulk` 엔드포인트 추가
- [ ] BE 암호화 저장 로직(AES-256-GCM) 확인 (기존 단건 등록과 동일 패턴 적용 가능한지)
- [ ] FE 확인 UI 디자인 (파싱 결과 테이블 레이아웃)
