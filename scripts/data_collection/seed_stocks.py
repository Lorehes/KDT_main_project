"""
[목적]
    코스피200 + 코스닥150 구성종목(약 350종목)을 KRX에서 수집하고
    DART corpCode.xml과 매핑해 Flyway V10 시드 SQL을 생성하는 일회성 스크립트.

[이유]
    stocks-master-seed Spec 카드 #5/#7 — 운영 환경에 `stocks` 마스터를 적재하는 SSOT.
    Java 백엔드에서 동적 시드(ApplicationRunner)를 하면 외부 API 의존이 부팅 경로에 들어가므로
    Flyway 마이그레이션(불변·재현성)으로 분리. 본 스크립트는 1회 실행 → SQL 산출 → 커밋.

[사이드 임팩트]
    출력 SQL은 `ON CONFLICT (stock_code) DO UPDATE`로 멱등 — 재실행 안전.
    DART corpCode.xml은 약 100k+ 기업 — 메모리 파싱 시 수MB 사용(허용 범위).
    분기 리밸런싱(편입제외) 후 재실행 시 새 V{n} 마이그레이션으로 분리 권장(V10 수정 금지).

[수정 시 고려사항]
    - DART_API_KEY 환경변수 필수. KRX는 공개 인덱스 데이터라 키 불필요.
    - pykrx는 비공식 라이브러리지만 KRX 정보데이터시스템을 안정적으로 래핑. 대안: KRX 공식 API.
    - sector(업종)는 pykrx의 get_market_sector_classifications 사용 가능 — 시점에 따라 다를 수 있음.
    - 출력 경로 변경 시 Flyway location(`db/migration`)에 맞춰 백엔드 리소스 디렉토리로 둘 것.

[사용법]
    cd scripts/data_collection
    pip install -r requirements.txt
    export DART_API_KEY=<your_key>
    python seed_stocks.py [--date YYYYMMDD] [--output ../../backend/src/main/resources/db/migration/V10__seed_stocks.sql]
"""

from __future__ import annotations

import argparse
import io
import os
import sys
import zipfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional
from xml.etree import ElementTree as ET

import requests

# pykrx import는 lazy — KRX 인덱스 구성종목 조회용
try:
    from pykrx import stock as krx_stock
except ImportError:
    print("[ERROR] pykrx 미설치 — `pip install -r requirements.txt`", file=sys.stderr)
    sys.exit(1)


DART_CORPCODE_URL = "https://opendart.fss.or.kr/api/corpCode.xml"
KOSPI200_INDEX = "1028"   # KRX 인덱스 코드 (KOSPI 200)
KOSDAQ150_INDEX = "2203"  # KRX 인덱스 코드 (KOSDAQ 150)


@dataclass(frozen=True)
class StockSeed:
    stock_code: str
    corp_code: str
    corp_name: str
    market: str     # KOSPI / KOSDAQ
    sector: Optional[str]


# ---------------------------------------------------------------------------
# 1) KRX 인덱스 구성종목 조회
# ---------------------------------------------------------------------------

def _extract_tickers(result) -> List[str]:
    """
    pykrx get_index_portfolio_deposit_file 반환 형태 차이 흡수.
    - Index/list: 그대로 list화
    - DataFrame: 인덱스(보통 종목코드)에서 추출
    """
    if result is None:
        return []
    if hasattr(result, "index") and hasattr(result, "columns"):
        idx_list = [str(x) for x in result.index.tolist()]
        if idx_list and all(len(c) == 6 and c.isdigit() for c in idx_list[:5]):
            return idx_list
        for col in result.columns:
            if str(col).lower() in ("종목코드", "ticker", "stock_code", "isu_srt_cd"):
                return [str(x) for x in result[col].tolist()]
        return idx_list
    try:
        return [str(x) for x in list(result)]
    except Exception:
        return []


def fetch_top_n_via_fdr(market: str, top_n: int, market_label: str) -> List[tuple[str, str]]:
    """
    [목적] FinanceDataReader로 시가총액 상위 N개 종목 조회 — pykrx 실패 시 폴백.
    [이유] FinanceDataReader는 KRX 외 다양한 소스를 사용해 KRX 서버 변경에 덜 민감.
    [수정 시 고려사항] FDR 버전에 따라 컬럼명 차이 — Code/Symbol, Marcap/시가총액 양쪽 대응.
    """
    try:
        import FinanceDataReader as fdr
    except ImportError:
        print(f"[ERROR] FinanceDataReader 미설치 — pip install finance-datareader", file=sys.stderr)
        return []

    try:
        df = fdr.StockListing(market)  # 'KOSPI' 또는 'KOSDAQ'
    except Exception as e:
        print(f"[ERROR] FDR {market} 조회 실패: {e}", file=sys.stderr)
        return []

    if df is None or len(df) == 0:
        print(f"[ERROR] FDR {market} 응답 0건", file=sys.stderr)
        return []

    # 시가총액 컬럼 탐색
    cap_col = next((c for c in ("Marcap", "시가총액", "MarketCap") if c in df.columns), None)
    code_col = next((c for c in ("Code", "Symbol", "종목코드") if c in df.columns), None)
    name_col = next((c for c in ("Name", "종목명") if c in df.columns), None)

    if not code_col or not name_col:
        print(f"[ERROR] FDR {market} 응답 컬럼 인식 실패. 컬럼: {df.columns.tolist()}", file=sys.stderr)
        return []

    if cap_col:
        df = df.dropna(subset=[cap_col]).sort_values(cap_col, ascending=False).head(top_n)
    else:
        df = df.head(top_n)
        print(f"[WARN] FDR {market} 시가총액 컬럼 없음 — 임의 상위 {top_n}개", file=sys.stderr)

    out: List[tuple[str, str]] = []
    for _, row in df.iterrows():
        code = str(row[code_col]).strip().zfill(6)
        if len(code) != 6 or not code.isdigit():
            continue
        name = str(row[name_col]).strip()
        out.append((code, name))
    print(f"[INFO] {market_label} FDR 상위 {len(out)}건 (시가총액 폴백)")
    return out


def fetch_top_n_by_market_cap(date: str, market: str, top_n: int, market_label: str) -> List[tuple[str, str]]:
    """
    [목적] 시가총액 상위 N개를 코스피200/코스닥150의 근사 폴백으로 사용.
    [이유] pykrx의 인덱스 API(get_index_portfolio_deposit_file)가 작동 안 할 때
           가장 안정적인 대안 — get_market_cap_by_ticker는 광범위 호환.
           실제 인덱스와 약 95%+ 일치(분기 리밸런싱 시 일시 차이).
    [수정 시 고려사항] 정확한 인덱스 구성이 필요하면 KRX 정보데이터시스템에서 수동 CSV 다운로드 후
                     --csv-input 옵션으로 입력(후속 도입 예정).
    """
    from datetime import timedelta
    candidate_date = date
    df = None
    for offset in range(0, 10):
        try_date_obj = datetime.strptime(date, "%Y%m%d")
        candidate_date = (try_date_obj - timedelta(days=offset)).strftime("%Y%m%d")
        try:
            df = krx_stock.get_market_cap_by_ticker(candidate_date, market=market)
            if df is not None and len(df) > 0:
                break
        except Exception as e:
            print(f"[WARN] {market} 시총 조회 실패 ({candidate_date}): {e}", file=sys.stderr)
            continue

    if df is None or len(df) == 0:
        print(f"[ERROR] {market_label} 시총 조회 0건 — KRX 서버/pykrx 상태 점검 필요", file=sys.stderr)
        return []

    # 시가총액 내림차순 상위 N개
    cap_col = "시가총액" if "시가총액" in df.columns else df.columns[0]
    df = df.sort_values(cap_col, ascending=False).head(top_n)
    out: List[tuple[str, str]] = []
    for code in df.index.tolist():
        code_str = str(code).zfill(6)
        try:
            name = krx_stock.get_market_ticker_name(code_str)
            out.append((code_str, name))
        except Exception:
            continue
    print(f"[INFO] {market_label} 시총 상위 {len(out)}건 (폴백, 기준일자 {candidate_date})")
    return out


def fetch_index_components(date: str, index_code: str, market_label: str) -> List[tuple[str, str]]:
    """
    [목적] 특정 인덱스(KOSPI200/KOSDAQ150)의 구성종목을 (stock_code, corp_name) 리스트로 반환.
    [이유] 종목 커버리지(통합기획서 §3.1) 확정.
           반환 형태 차이(Index vs DataFrame)와 비영업일(빈 응답) 모두 대응.
    """
    # 비영업일/장 시작 전 대비 — 빈 응답이면 직전 영업일들로 후퇴 시도
    candidate_date = date
    tickers: List[str] = []
    for offset in range(0, 10):  # 최대 10일 후퇴
        try_date_obj = datetime.strptime(date, "%Y%m%d")
        from datetime import timedelta
        candidate_date = (try_date_obj - timedelta(days=offset)).strftime("%Y%m%d")
        try:
            result = krx_stock.get_index_portfolio_deposit_file(candidate_date, index_code)
        except Exception as e:
            print(f"[WARN] KRX 인덱스 조회 실패 ({candidate_date}, {index_code}): {e}", file=sys.stderr)
            continue
        tickers = _extract_tickers(result)
        if tickers:
            if offset > 0:
                print(f"[INFO] {market_label} — 기준일자 {date} 빈 응답, {candidate_date}로 후퇴", file=sys.stderr)
            break

    if not tickers:
        print(f"[ERROR] {market_label} 인덱스({index_code}) 구성종목 0건 — pykrx 응답 확인 필요. "
              f"진단: python -c \"from pykrx import stock; r=stock.get_index_portfolio_deposit_file('{date}','{index_code}'); print(type(r), r)\"",
              file=sys.stderr)
        return []

    out: List[tuple[str, str]] = []
    for code in tickers:
        # 6자리 종목코드만 — 컬럼명 등 잘못된 추출 방어
        if not (len(code) == 6 and code.isdigit()):
            continue
        try:
            name = krx_stock.get_market_ticker_name(code)
            out.append((code, name))
        except Exception as e:
            print(f"[WARN] KRX 종목명 조회 실패 stock_code={code}: {e}", file=sys.stderr)
    print(f"[INFO] {market_label} 인덱스({index_code}) 구성종목 {len(out)}건 수집 (기준일자 {candidate_date})")
    return out


def fetch_sectors(stock_codes: List[str], date: str) -> Dict[str, str]:
    """
    [목적] 종목별 업종(섹터) 분류 정보.
    [이유] stocks.sector 컬럼 채움. KRX 업종 분류 사용.
    """
    sectors: Dict[str, str] = {}
    # KOSPI/KOSDAQ 각각 업종 정보 조회
    for market in ("KOSPI", "KOSDAQ"):
        try:
            df = krx_stock.get_market_sector_classifications(date, market=market)
            if df is None:
                continue
            for code, row in df.iterrows():
                # df 컬럼: ['종목명', '업종명', ...] (pykrx 버전 의존)
                sector = row.get("업종명") if hasattr(row, "get") else None
                if code in stock_codes and sector:
                    sectors[code] = str(sector)[:100]
        except Exception as e:
            print(f"[WARN] {market} 업종 조회 실패: {e}", file=sys.stderr)
    print(f"[INFO] 섹터 매핑 {len(sectors)}/{len(stock_codes)}건")
    return sectors


# ---------------------------------------------------------------------------
# 2) DART corpCode.xml 다운로드 + 매핑
# ---------------------------------------------------------------------------

def fetch_dart_corp_code_map(api_key: str) -> Dict[str, tuple[str, str]]:
    """
    [목적] DART 고유번호 사전 — `{stock_code: (corp_code, corp_name)}`.
           stock_code가 비어있는(비상장) 항목은 제외.
    [이유] disclosure의 corp_code FK 매핑 + stocks.corp_code 채움.
    [사이드 임팩트] DART API 1회 호출(분기 1회 권장). zip ~1MB, 압축해제 ~10MB.
    """
    print(f"[INFO] DART corpCode.xml 다운로드 시작...")
    resp = requests.get(DART_CORPCODE_URL, params={"crtfc_key": api_key}, timeout=30)
    resp.raise_for_status()

    # 응답이 JSON 에러일 수 있음 (잘못된 키 등)
    if resp.headers.get("content-type", "").startswith("application/json"):
        raise RuntimeError(f"DART API 오류 응답: {resp.text[:200]}")

    with zipfile.ZipFile(io.BytesIO(resp.content)) as zf:
        with zf.open("CORPCODE.xml") as xml_file:
            tree = ET.parse(xml_file)

    root = tree.getroot()
    mapping: Dict[str, tuple[str, str]] = {}
    for item in root.findall("list"):
        corp_code = (item.findtext("corp_code") or "").strip()
        corp_name = (item.findtext("corp_name") or "").strip()
        stock_code = (item.findtext("stock_code") or "").strip()
        if stock_code and len(stock_code) == 6:
            mapping[stock_code] = (corp_code, corp_name)

    print(f"[INFO] DART 상장사 매핑 {len(mapping)}건")
    return mapping


# ---------------------------------------------------------------------------
# 3) 조합 + V10 SQL 생성
# ---------------------------------------------------------------------------

def build_seeds(
    kospi200: List[tuple[str, str]],
    kosdaq150: List[tuple[str, str]],
    sectors: Dict[str, str],
    dart_map: Dict[str, tuple[str, str]],
) -> List[StockSeed]:
    seeds: List[StockSeed] = []
    skipped: List[str] = []

    for items, market in ((kospi200, "KOSPI"), (kosdaq150, "KOSDAQ")):
        for stock_code, krx_name in items:
            if stock_code not in dart_map:
                skipped.append(f"{stock_code}({krx_name})")
                continue
            corp_code, dart_name = dart_map[stock_code]
            # DART 원본 corp_name 우선 (LLM 변형 금지 정책과 동일 결)
            corp_name = dart_name or krx_name
            seeds.append(StockSeed(
                stock_code=stock_code,
                corp_code=corp_code,
                corp_name=corp_name[:100],
                market=market,
                sector=sectors.get(stock_code),
            ))

    if skipped:
        print(f"[WARN] DART 매핑 없음(skip) {len(skipped)}건: {', '.join(skipped[:10])}{'...' if len(skipped) > 10 else ''}",
              file=sys.stderr)
    print(f"[INFO] 최종 시드 {len(seeds)}건")
    return seeds


def sql_escape(s: str) -> str:
    return s.replace("'", "''")


def generate_sql(seeds: List[StockSeed], generated_at: str) -> str:
    header = f"""-- V10__seed_stocks.sql
-- [목적] 코스피200 + 코스닥150 구성종목 시드 (stocks-master-seed Spec 카드 #7).
-- [이유] 공시 커버 필터(disclosure)와 portfolios FK의 기준 데이터.
-- [사이드 임팩트] ON CONFLICT DO UPDATE로 멱등 — 재적용 안전.
--               분기 리밸런싱은 V{{n}}__resync_stocks.sql로 새 마이그레이션(본 파일 수정 금지).
-- [생성일] {generated_at} (scripts/data_collection/seed_stocks.py 산출)

INSERT INTO stocks (stock_code, corp_code, corp_name, market, sector) VALUES
"""
    rows = []
    for s in seeds:
        sector_sql = f"'{sql_escape(s.sector)}'" if s.sector else "NULL"
        rows.append(
            f"  ('{s.stock_code}', '{s.corp_code}', '{sql_escape(s.corp_name)}', '{s.market}', {sector_sql})"
        )
    body = ",\n".join(rows)
    footer = """
ON CONFLICT (stock_code) DO UPDATE
SET corp_code  = EXCLUDED.corp_code,
    corp_name  = EXCLUDED.corp_name,
    market     = EXCLUDED.market,
    sector     = EXCLUDED.sector,
    updated_at = now();
"""
    return header + body + footer


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    default_out = Path(__file__).resolve().parents[2] / "backend" / "src" / "main" / "resources" / "db" / "migration" / "V10__seed_stocks.sql"
    p = argparse.ArgumentParser(description="코스피200+코스닥150 → V10 시드 SQL 생성")
    p.add_argument("--date", default=datetime.now().strftime("%Y%m%d"),
                   help="KRX 인덱스 기준일자 (YYYYMMDD). 기본: 오늘")
    p.add_argument("--output", default=str(default_out),
                   help="출력 SQL 경로 (기본: backend/.../db/migration/V10__seed_stocks.sql)")
    p.add_argument("--source", choices=["index", "marketcap", "fdr"], default="index",
                   help="종목 출처: index=pykrx 인덱스(엄격) / marketcap=pykrx 시총 폴백 / fdr=FinanceDataReader(가장 안정)")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    api_key = os.environ.get("DART_API_KEY")
    if not api_key:
        print("[ERROR] DART_API_KEY 환경변수 미설정", file=sys.stderr)
        return 2

    print(f"[INFO] 기준일자: {args.date}, source={args.source}")

    # 1. KRX 종목 수집 — 단계적 폴백: index → marketcap → fdr
    if args.source == "fdr":
        kospi200 = fetch_top_n_via_fdr("KOSPI", 200, "KOSPI200")
        kosdaq150 = fetch_top_n_via_fdr("KOSDAQ", 150, "KOSDAQ150")
    elif args.source == "marketcap":
        kospi200 = fetch_top_n_by_market_cap(args.date, "KOSPI", 200, "KOSPI200")
        kosdaq150 = fetch_top_n_by_market_cap(args.date, "KOSDAQ", 150, "KOSDAQ150")
        if not kospi200 and not kosdaq150:
            print("[WARN] pykrx 시총 폴백 실패 — FinanceDataReader로 자동 전환", file=sys.stderr)
            kospi200 = fetch_top_n_via_fdr("KOSPI", 200, "KOSPI200")
            kosdaq150 = fetch_top_n_via_fdr("KOSDAQ", 150, "KOSDAQ150")
    else:  # index
        kospi200 = fetch_index_components(args.date, KOSPI200_INDEX, "KOSPI200")
        kosdaq150 = fetch_index_components(args.date, KOSDAQ150_INDEX, "KOSDAQ150")
        if not kospi200 and not kosdaq150:
            print("[WARN] 인덱스 API 양쪽 실패 — pykrx 시총 폴백 시도", file=sys.stderr)
            kospi200 = fetch_top_n_by_market_cap(args.date, "KOSPI", 200, "KOSPI200")
            kosdaq150 = fetch_top_n_by_market_cap(args.date, "KOSDAQ", 150, "KOSDAQ150")
        if not kospi200 and not kosdaq150:
            print("[WARN] pykrx 전체 실패 — FinanceDataReader로 최종 폴백", file=sys.stderr)
            kospi200 = fetch_top_n_via_fdr("KOSPI", 200, "KOSPI200")
            kosdaq150 = fetch_top_n_via_fdr("KOSDAQ", 150, "KOSDAQ150")

    # 2. 업종 정보
    all_codes = [c for c, _ in kospi200 + kosdaq150]
    sectors = fetch_sectors(all_codes, args.date)

    # 3. DART 매핑
    dart_map = fetch_dart_corp_code_map(api_key)

    # 4. 조합
    seeds = build_seeds(kospi200, kosdaq150, sectors, dart_map)
    if not seeds:
        print("[ERROR] 시드 0건 — 데이터 출처/매핑 점검 필요", file=sys.stderr)
        return 3

    # 5. SQL 생성
    sql = generate_sql(seeds, generated_at=f"{datetime.now().isoformat(timespec='seconds')} (기준일 {args.date})")
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(sql, encoding="utf-8")
    print(f"[OK] {out_path} ({len(seeds)} rows)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
