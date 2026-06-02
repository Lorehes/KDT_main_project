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

def fetch_index_components(date: str, index_code: str, market_label: str) -> List[tuple[str, str]]:
    """
    [목적] 특정 인덱스(KOSPI200/KOSDAQ150)의 구성종목을 (stock_code, corp_name) 리스트로 반환.
    [이유] 종목 커버리지(통합기획서 §3.1) 확정.
    """
    df = krx_stock.get_index_portfolio_deposit_file(date, index_code)
    # pykrx는 종목코드 리스트만 반환 — 종목명은 별도 조회
    tickers: List[str] = list(df) if df is not None else []
    out: List[tuple[str, str]] = []
    for code in tickers:
        try:
            name = krx_stock.get_market_ticker_name(code)
            out.append((code, name))
        except Exception as e:
            print(f"[WARN] KRX 종목명 조회 실패 stock_code={code}: {e}", file=sys.stderr)
    print(f"[INFO] {market_label} 인덱스({index_code}) 구성종목 {len(out)}건 수집")
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
    return p.parse_args()


def main() -> int:
    args = parse_args()
    api_key = os.environ.get("DART_API_KEY")
    if not api_key:
        print("[ERROR] DART_API_KEY 환경변수 미설정", file=sys.stderr)
        return 2

    print(f"[INFO] 기준일자: {args.date}")

    # 1. KRX 인덱스 구성
    kospi200 = fetch_index_components(args.date, KOSPI200_INDEX, "KOSPI200")
    kosdaq150 = fetch_index_components(args.date, KOSDAQ150_INDEX, "KOSDAQ150")

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
