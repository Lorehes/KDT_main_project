---
type: spec
status: Draft
created: 2026-05-30
updated: 2026-05-30
---

# 종목 마스터 시드/동기화 (stocks) Spec

> 상태: **Draft** (dc-plan 생성)
> 관계: [[disclosure-collection-pipeline]]의 **선행 작업**(커버 종목 필터가 이 데이터에 의존).
> 연계 SSOT: [[DART공시통역_통합기획서]] §3.1·§7.3 · [[db_schema]] §3.2 · [[api_spec]] §3.1·§3.2 · [[feature_structure]] §4

## 배경 / 목적

- **문제**: `stocks`(V2) 테이블이 비어 있어 ①공시 **커버 종목 필터**([[disclosure-collection-pipeline]])와 ②`portfolios` FK(`stock_code`)가 동작하지 않는다.
- **목적**: 코스피200 + 코스닥150 = **약 350종목**(통합기획서 §3.1) 마스터를 `stocks`에 적재하고, DART 고유번호(`corp_code`)↔KRX 종목코드(`stock_code`)를 매핑한다.
- **BM 티어**: 전 티어 공통 인프라(기준 데이터).

## 요구사항

### 기능
- [ ] KRX 종목 기본정보(코스피200·코스닥150 선정) → `stocks` 적재(`stock_code`·`corp_name`·`market`·`sector`)
- [ ] DART `corpCode.xml`(고유번호 zip)에서 `corp_code` 매핑 → `stocks.corp_code`
- [ ] 분기 1회 동기화 잡(`StockMasterSyncJob`, [[feature_structure]] §4)로 갱신
- [ ] 멱등 upsert(이미 존재하는 `stock_code`는 갱신)

### 비기능
- [ ] 외부 호출 타임아웃·재시도, 키는 환경변수(`DART_API_KEY`·`KRX_API_KEY`)
- [ ] 350종목 선정 기준 명확화(지수 구성 종목 출처)

## 영향 범위 (조사 결과)

- **영향 레이어**: backend(`infrastructure`) + `stocks` 접근 레포(귀속 도메인은 tech-review에서 — 보조 마스터라 `infrastructure` 동기화 잡 + 공용 레포 권장)
- **신규 파일(예상)**:
  - `infrastructure/krx/KrxClient.java`, `infrastructure/dart/DartCorpCodeClient.java`
  - `Stock` 엔티티 + `StockRepository`(귀속 위치 tech-review)
  - `StockMasterSyncJob`
- **DB 변경**: 없음(`stocks` V2 이미 존재). 데이터 적재만
- **외부 계약**: KRX 종목 기본정보 API([[api_spec]] §3.2 — **엔드포인트·파라미터 미확정, 확인 필요**), DART `corpCode.xml`([[api_spec]] §3.1)

## 관련 패턴 / 과거 사례

- 첫 기능군(참고 구현 없음). 대안: **`scripts/data_collection/`(Python) 1회 시드** 후 백엔드 분기 동기화로 운영(통합기획서 §5.4) — tech-review에서 선택.

## 리스크 / 법적 검토

- 법적: 공개 기준 데이터 — 자본시장법/개인정보 무관.
- 운영:
  - **확인 필요**: KRX OpenAPI 정확한 서비스 경로·파라미터([[api_spec]] §3.2 미확정).
  - 코스피200/코스닥150 **구성 종목 출처**(지수 편입 리스트) 확정 필요 — 분기 리밸런싱 반영.
  - `corp_code`↔`stock_code` 매핑 누락/불일치(비상장·우선주 제외, 통합기획서 §3.1).

## 권장 구현 방향

- **MVP**: 350종목 **1회 시드**(스크립트 또는 부팅 시 적재) → 즉시 [[disclosure-collection-pipeline]] 필터 가능하게.
- **운영**: 분기 `StockMasterSyncJob`으로 자동 갱신(후속).
- 시드 데이터 소스(KRX API vs 공개 CSV vs 스크립트)는 tech-review에서 확정.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
