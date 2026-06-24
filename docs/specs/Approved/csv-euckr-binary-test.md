---
type: spec
status: Approved
created: 2026-06-24
updated: 2026-06-24
---

# parsePortfolioCsv EUC-KR 실인코딩 바이너리 테스트 보강 Spec

> 상태: Draft → **Approved** (2026-06-24, dc-tech-review 승인 — portfolio-csv-upload와 동일 wave 묶기)

## 배경 / 목적

`parsePortfolioCsv` 는 증권사 CSV의 EUC-KR/UTF-8 이중 인코딩을 처리한다.
현재 테스트(`parsePortfolioCsv.test.ts`)의 인코딩 섹션은 UTF-8 문자열 파일만 사용하며,
`TextDecoder("euc-kr", { fatal: true })` 의 **성공 경로**와 **실패→UTF-8 폴백 경로**를
각각 독립적으로 검증하지 않는다.

실제 키움·삼성·NH 증권사 CSV는 EUC-KR 바이너리이므로,
해당 분기를 커버하지 않으면 인코딩 회귀를 조기에 감지할 수 없다.

- **영향 페르소나**: A(장기 투자자 — 증권사 CSV 보유), C(일반 투자자 — 다수 종목)
- **식별 경위**: dc-review-code 리뷰 Medium 이슈 (`portfolio-csv-upload` 구현 후 2026-06-24)

## 리서치 결과

### Node.js v22 환경 EUC-KR 지원 확인 (2026-06-24 실행)

```
# 검증 결과
euc-kr 지원: YES
0xA1 0xA1 → "　005930\n"  (KS X 1001 ideographic space + ASCII)
0xFF       → throw "The encoded data was not valid for encoding euc-kr"
UTF-8 폴백 결과: "005930\n" (0xFF → U+FFFD 대체문자)
종목코드 추출: true
```

### 핵심 바이너리 패턴

| 패턴 | 바이트 | 의미 |
|------|--------|------|
| EUC-KR 유효 문자 | `0xA1 0xA1` | KS X 1001 이상적 공백(U+3000) — 2바이트 EUC-KR 인코딩 |
| EUC-KR 무효 바이트 | `0xFF` | EUC-KR 범위(0xA1~0xFE) 초과 → fatal: true 시 throw |
| ASCII 종목코드 | `0x30 0x30 0x35 0x39 0x33 0x30` | "005930" — 인코딩 무관 동일 |

### ICU 의존성 리스크

`TextDecoder("euc-kr")` 는 Node.js ICU 데이터에 의존한다:
- `full-icu` (Node.js v22 기본): EUC-KR 지원 ✅
- `small-icu` (일부 경량 빌드/CI): EUC-KR 미지원 → `RangeError: The encoding label provided ('euc-kr') is invalid`

CI 환경에 따라 테스트가 예상치 못한 이유로 실패할 수 있으므로
런타임 지원 여부를 사전 감지(skipIf 패턴)해야 한다.

## 요구사항

- [ ] **EUC-KR 성공 경로 테스트**: `[0xA1, 0xA1, ...ASCII stock code..., 0x0A]` 바이너리로 파일 생성 → EUC-KR decode 성공 → 종목코드 추출 확인
- [ ] **EUC-KR 실패 → UTF-8 폴백 경로 테스트**: `[0xFF, ...ASCII stock code..., 0x0A]` 바이너리 → EUC-KR `fatal: true` throw → UTF-8 폴백 → 종목코드 추출 확인
- [ ] **ICU 지원 여부 런타임 감지**: `TextDecoder("euc-kr")` 생성 실패 시 `it.skipIf(...)` 패턴으로 EUC-KR 경로 테스트 조건부 스킵
- [ ] **기존 12개 테스트 케이스 유지**: 추가만 하고 기존 테스트 제거 금지

## 영향 범위

- **영향 레이어**: frontend/test 단독 — 구현 코드(`parsePortfolioCsv.ts`) 변경 없음
- **영향 파일**:
  - `frontend/src/lib/csv/parsePortfolioCsv.test.ts` — 테스트 케이스 추가
- **DB 변경**: 없음
- **외부 계약**: 없음

## 관련 패턴 / 과거 사례

- 구현 코드: `frontend/src/lib/csv/parsePortfolioCsv.ts` — 3개 분기(BOM·EUC-KR·UTF-8 폴백)
- 기존 헬퍼: `makeUtf8BomFile(content)` — `Uint8Array` + `ArrayBuffer as ArrayBuffer` 패턴 참고
- Vitest `it.skipIf(condition)` API — 런타임 조건부 스킵 표준 방법

## 권장 구현 방향

### 테스트 추가 예시

```typescript
// ── EUC-KR 지원 여부 런타임 감지 ─────────────────────────────────────────────
const eucKrSupported = (() => {
  try { new TextDecoder("euc-kr", { fatal: true }); return true; }
  catch { return false; }
})();

describe("parsePortfolioCsv — EUC-KR 실인코딩", () => {
  it.skipIf(!eucKrSupported)("EUC-KR 성공 경로 — 유효 2바이트 시퀀스에서 종목코드를 추출한다", async () => {
    // 0xA1 0xA1 = KS X 1001 이상적 공백(U+3000), valid EUC-KR 2바이트
    // 0x30..0x30 = "005930" (ASCII, 인코딩 무관)
    const bytes = new Uint8Array([0xA1, 0xA1, 0x30, 0x30, 0x35, 0x39, 0x33, 0x30, 0x0A]);
    const file = new File([bytes.buffer as ArrayBuffer], "euckr.csv");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("005930");
  });

  it.skipIf(!eucKrSupported)("EUC-KR 실패 → UTF-8 폴백 — 0xFF 무효 바이트 포함 시에도 종목코드를 추출한다", async () => {
    // 0xFF = EUC-KR 범위(0xA1~0xFE) 초과 → fatal: true throw → UTF-8 폴백
    // UTF-8 폴백 시 0xFF → U+FFFD (대체문자), 종목코드는 ASCII라 영향 없음
    const bytes = new Uint8Array([0xFF, 0x30, 0x30, 0x35, 0x39, 0x33, 0x30, 0x0A]);
    const file = new File([bytes.buffer as ArrayBuffer], "fallback.csv");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("005930");
  });
});
```

### 구현 시 주의사항

1. **ICU skipIf 처리**: 모든 EUC-KR 관련 케이스에 `it.skipIf(!eucKrSupported)` 적용 — 누락 시 CI small-icu 환경에서 false failure
2. **`Uint8Array.buffer as ArrayBuffer` 캐스팅**: 기존 `makeUtf8BomFile` 패턴 동일 적용
3. **실제 한글 EUC-KR 바이트 추가 옵션**: 향후 "삼성전자" 등 실제 KS X 1001 한글 바이트 추가 시 정확한 바이트값은 외부 변환 도구로 검증 후 하드코딩

## 리스크 / 법적 검토

- **ICU 환경 의존**: CI/CD 서버 Node.js 빌드 설정에 따라 EUC-KR 지원이 다름. `eucKrSupported` 런타임 감지 + `skipIf` 필수.
- **자본시장법**: 해당 없음 (테스트 유틸 파일, 실제 금융 데이터 없음)
- **개인정보**: 테스트 파일에 실제 종목코드 하드코딩 시 공개 마스터 데이터(코스피200+코스닥150)이므로 PII 아님

## 예상 wave 수

- **1 wave** (FE 테스트 단독) — `parsePortfolioCsv.test.ts`만 수정. 구현 코드 변경 없음.

## Tech Review (dc-tech-review · 2026-06-24)

### 코드 대조로 확인된 사실

1. **`it.skipIf` 가용성 확인 완료.** `package.json` vitest `^4.1.9` — `it.skipIf(condition)`는 vitest 3.x+ 표준 API로 지원됨. 현재 코드베이스에 사용처는 없으나(첫 도입), 표준 API라 추가 의존성 불필요.
2. **`Uint8Array.buffer as ArrayBuffer` 패턴 검증됨.** 기존 `makeUtf8BomFile`(`parsePortfolioCsv.test.ts:11-19`)이 동일 캐스팅을 이미 사용 — `tsc --noEmit` 통과 확인된 패턴. 신규 EUC-KR 바이너리 헬퍼도 동일 패턴 적용.
3. **Node v22 EUC-KR 런타임 지원 확인됨(dc-plan Step).** `0xA1 0xA1`→"　", `0xFF`→fatal throw 동작 검증 완료. 단 CI 환경의 ICU 빌드는 별개이므로 `eucKrSupported` 런타임 가드 필수.

### 아키텍처 분해
- 영향 레이어: **frontend/test 단독** — 구현 코드(`parsePortfolioCsv.ts`) 무변경
- 신규: EUC-KR 바이너리 헬퍼(`makeEucKrFile` 또는 인라인 `Uint8Array`), `eucKrSupported` 런타임 감지 IIFE
- 수정: `parsePortfolioCsv.test.ts`에 `describe("EUC-KR 실인코딩")` 블록 추가 (기존 12케이스 유지)

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `eucKrSupported` 런타임 감지 IIFE + EUC-KR 바이너리 File 헬퍼 추가 | frontend/test | FE | 하 | - |
| 2 | EUC-KR 성공 경로 케이스 — `[0xA1,0xA1,...006자리...]` decode 성공 → 추출 검증 (`it.skipIf` 가드) | frontend/test | FE | 하 | #1 |
| 3 | EUC-KR 실패→UTF-8 폴백 케이스 — `[0xFF,...006자리...]` throw→폴백→추출 검증 (`it.skipIf` 가드) | frontend/test | FE | 중 | #1 |

### DB / 마이그레이션 영향
- **없음.** 테스트 파일 단독 수정. Flyway 무관.

### 외부 계약 영향
- **없음.** DART/KRX/카카오/LLM 무관. 네트워크 호출 없는 순수 디코딩 테스트.

### 리스크 & 법적 검토
- **ICU 환경 의존(기술 리스크)**: CI Node.js가 small-icu 빌드면 `TextDecoder("euc-kr")` 생성이 `RangeError`. `eucKrSupported` IIFE로 감지해 `skipIf` 처리 — 미적용 시 CI false failure. (카드 1에서 우선 처리, 카드 2·3이 의존)
- **개인정보**: 테스트에 하드코딩하는 종목코드는 공개 마스터(코스피200+코스닥150) — PII 아님.
- **자본시장법**: 해당 없음 (테스트 유틸).

### 예상 wave 수
- **1 wave** — 카드 1~3을 한 PR로. 구현 코드 변경 없어 회귀 위험 최소.
