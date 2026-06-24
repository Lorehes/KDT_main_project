// [목적] 증권사 거래내역 CSV 파일에서 6자리 종목코드만 추출 (EUC-KR/UTF-8 이중 인코딩 지원)
// [이유] 증권사 CSV는 EUC-KR(키움·삼성·NH 등) 또는 UTF-8(BOM 포함) 인코딩 혼재.
//   TextDecoder("euc-kr", { fatal: true })로 시도 후 실패 시 UTF-8 폴백으로 커버.
//   종목코드는 ASCII 6자리라 인코딩 영향을 받지 않으므로 추출 정확도는 인코딩과 무관.
// [사이드 임팩트] 순수 FE 유틸 — 네트워크 호출 없음. CSV 원본은 메모리에서 처리 후 버려짐.
//   반환값은 종목코드(string[])만 — 매수가·수량 등 금융 개인정보는 추출·반환하지 않음(CLAUDE.md §7).
// [수정 시 고려사항] /\b\d{6}\b/g 정규식은 날짜(YYYYMMDD)·계좌번호 등 다른 숫자열을 포함할 수 있음.
//   실제 국내 종목코드가 모두 6자리이므로 허용하며, BE가 400으로 미지원 코드를 필터링.
//   TextDecoder의 "euc-kr" 지원 여부는 브라우저 표준(WHATWG Encoding spec)으로 보장됨.

export async function parsePortfolioCsv(file: File): Promise<string[]> {
  const buffer = await file.arrayBuffer();
  const bytes = new Uint8Array(buffer);

  // UTF-8 BOM(0xEF 0xBB 0xBF) 감지 → UTF-8로 직접 처리
  const hasUtf8Bom = bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf;

  let text: string;
  if (hasUtf8Bom) {
    text = new TextDecoder("utf-8").decode(buffer.slice(3));
  } else {
    try {
      // EUC-KR 시도 — fatal: true로 잘못된 시퀀스 시 즉시 throw
      text = new TextDecoder("euc-kr", { fatal: true }).decode(buffer);
    } catch {
      // UTF-8 폴백 — EUC-KR 실패(UTF-8 파일 등) 대비
      text = new TextDecoder("utf-8").decode(buffer);
    }
  }

  const codes = new Set<string>();
  for (const line of text.split(/\r?\n/)) {
    const matches = line.match(/\b\d{6}\b/g) ?? [];
    for (const m of matches) codes.add(m);
  }

  return [...codes];
}
