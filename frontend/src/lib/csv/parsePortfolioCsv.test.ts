import { describe, it, expect } from "vitest";
import { parsePortfolioCsv } from "./parsePortfolioCsv";

// ── File 생성 헬퍼 ────────────────────────────────────────────────────────────

function makeFile(content: string, name = "test.csv"): File {
  // 문자열을 직접 BlobPart로 전달 — Uint8Array 타입 불일치 우회
  return new File([content], name, { type: "text/csv" });
}

function makeUtf8BomFile(content: string): File {
  const bom = new Uint8Array([0xef, 0xbb, 0xbf]);
  const body = new TextEncoder().encode(content);
  const merged = new Uint8Array(bom.length + body.length);
  merged.set(bom, 0);
  merged.set(body, bom.length);
  // buffer를 ArrayBuffer로 캐스팅해 BlobPart 타입 충족
  return new File([merged.buffer as ArrayBuffer], "bom.csv", { type: "text/csv" });
}

// ── 기본 추출 ─────────────────────────────────────────────────────────────────

describe("parsePortfolioCsv — 기본 종목코드 추출", () => {
  it("단일 종목코드를 추출한다", async () => {
    const file = makeFile("code,name\n005930,삼성전자\n");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("005930");
  });

  it("여러 줄에서 복수 종목코드를 추출한다", async () => {
    const file = makeFile("005930,삼성전자\n000660,SK하이닉스\n035720,카카오\n");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("005930");
    expect(result).toContain("000660");
    expect(result).toContain("035720");
    expect(result.length).toBe(3);
  });

  it("중복 종목코드를 제거한다 (Set dedup)", async () => {
    const file = makeFile("005930,삼성전자,100\n005930,삼성전자,200\n");
    const result = await parsePortfolioCsv(file);
    expect(result.filter(c => c === "005930").length).toBe(1);
  });

  it("CRLF 줄바꿈(Windows CSV)을 처리한다", async () => {
    const file = makeFile("005930,삼성전자\r\n000660,SK하이닉스\r\n");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("005930");
    expect(result).toContain("000660");
  });
});

// ── 인코딩 처리 ───────────────────────────────────────────────────────────────

describe("parsePortfolioCsv — 인코딩", () => {
  it("UTF-8 BOM 파일을 처리한다", async () => {
    const file = makeUtf8BomFile("005930,삼성전자\n000660,SK하이닉스\n");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("005930");
    expect(result).toContain("000660");
  });

  it("한글이 포함된 UTF-8 CSV에서 종목코드를 추출한다", async () => {
    // 한글(멀티바이트 UTF-8)이 포함된 파일에서 ASCII 6자리 종목코드는 인코딩 무관하게 추출됨
    // EUC-KR decode 시도 후 성공/실패 여부에 무관하게 종목코드가 반환되어야 함
    const file = makeFile("035720,카카오,50,4\n");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("035720");
  });
});

// ── EUC-KR 실인코딩 바이너리 ─────────────────────────────────────────────────
//
// [카드 1] eucKrSupported 런타임 감지 IIFE
//   TextDecoder("euc-kr") 생성 가능 여부를 사전 확인한다.
//   Node.js small-icu 빌드(일부 CI)에서는 EUC-KR 인코딩 라벨 자체를 모를 수 있으므로
//   skipIf 가드 없이 실행하면 RangeError로 false failure 발생.
//
// [카드 2] EUC-KR 성공 경로 — 0xA1 0xA1 (KS X 1001 이상적 공백 U+3000, 유효 2바이트)
//   EUC-KR decode가 성공해야 하는 경로. ASCII 종목코드는 그대로 추출됨.
//
// [카드 3] EUC-KR 실패 → UTF-8 폴백 — 0xFF (EUC-KR 범위 0xA1~0xFE 초과, 즉시 throw)
//   TextDecoder("euc-kr", { fatal: true })가 throw → UTF-8 폴백 → 0xFF → U+FFFD 대체
//   종목코드는 ASCII이므로 인코딩 오류 영향 없이 추출됨.

const eucKrSupported = (() => {
  try { new TextDecoder("euc-kr", { fatal: true }); return true; }
  catch { return false; }
})();

describe("parsePortfolioCsv — EUC-KR 실인코딩 바이너리", () => {
  it.skipIf(!eucKrSupported)("EUC-KR 성공 경로 — 유효 2바이트 시퀀스에서 종목코드를 추출한다", async () => {
    // 0xA1 0xA1 = KS X 1001 row1·col1 (U+3000 이상적 공백), valid EUC-KR 2바이트
    // Node v22 실행 검증: TextDecoder("euc-kr").decode([0xA1,0xA1,...]) → "　005930\n"
    const bytes = new Uint8Array([
      0xA1, 0xA1,                               // KS X 1001 유효 EUC-KR 2바이트 문자
      0x30, 0x30, 0x35, 0x39, 0x33, 0x30, 0x0A // "005930\n" (ASCII)
    ]);
    const file = new File([bytes.buffer as ArrayBuffer], "euckr.csv");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("005930");
  });

  it.skipIf(!eucKrSupported)("EUC-KR 실패 → UTF-8 폴백 — 무효 바이트 포함 시에도 종목코드를 추출한다", async () => {
    // 0xFF = EUC-KR 유효 범위(0xA1~0xFE) 초과 → TextDecoder("euc-kr", {fatal:true}) throw
    // UTF-8 폴백: 0xFF → U+FFFD 대체문자. ASCII 종목코드는 영향 없이 추출됨.
    // Node v22 실행 검증: fallback 결과 "005930" 추출 확인.
    const bytes = new Uint8Array([
      0xFF,                                     // EUC-KR 무효 바이트 → fatal throw 유발
      0x30, 0x30, 0x35, 0x39, 0x33, 0x30, 0x0A // "005930\n" (ASCII)
    ]);
    const file = new File([bytes.buffer as ArrayBuffer], "fallback.csv");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("005930");
  });
});

// ── 경계값 / 비정상 입력 ──────────────────────────────────────────────────────

describe("parsePortfolioCsv — 경계값", () => {
  it("빈 CSV 파일은 빈 배열을 반환한다", async () => {
    const file = makeFile("");
    const result = await parsePortfolioCsv(file);
    expect(result).toEqual([]);
  });

  it("종목코드가 없는 CSV는 빈 배열을 반환한다", async () => {
    const file = makeFile("header,name,date\n일반텍스트,회사명,2024-01-01\n");
    const result = await parsePortfolioCsv(file);
    expect(result).toEqual([]);
  });

  it("5자리 숫자는 추출하지 않는다", async () => {
    const file = makeFile("12345,다섯자리\n");
    const result = await parsePortfolioCsv(file);
    expect(result).not.toContain("12345");
  });

  it("7자리 숫자는 추출하지 않는다", async () => {
    const file = makeFile("1234567,일곱자리\n");
    const result = await parsePortfolioCsv(file);
    expect(result).not.toContain("1234567");
  });

  it("헤더 행에 포함된 종목코드도 추출한다 (포맷 무관 전략)", async () => {
    const file = makeFile("005930,거래량,수익\n");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("005930");
  });

  it("같은 줄에 여러 종목코드가 있으면 모두 추출한다", async () => {
    const file = makeFile("005930 000660 035720\n");
    const result = await parsePortfolioCsv(file);
    expect(result).toContain("005930");
    expect(result).toContain("000660");
    expect(result).toContain("035720");
  });
});
