---
type: spec
status: Draft
created: 2026-06-24
updated: 2026-06-24
---

# KRX 종가 소스 신뢰성 강화 Spec

> 상태: **Draft** (dc-plan 생성 — dc-review-code MEDIUM 이슈 기록)

## 배경 / 목적

`dashboard-eval-pnl` 구현 코드리뷰(`/dc-review-code`) 결과, `KrxClient`의 GitHub cache CSV 폴백이 외부 개인 레포(`FinanceData/fdr_krx_data_cache`)에 무조건 의존하며 데이터 무결성 검증이 없음이 MEDIUM 이슈로 확인됨.

현재 구조적 문제:
1. **공급망 리스크**: `GITHUB_CACHE_URL`(`raw.githubusercontent.com/FinanceData/fdr_krx_data_cache/...`)은 외부 개인 계정 소유 레포다. 계정 침해·레포 삭제·포맷 변경 시 종가 수집 완전 중단.
2. **호스트 화이트리스트 우회**: `HostWhitelist.verify()`는 `props.baseUrl()`(= `data.krx.co.kr`)만 검증. `externalRestClient`가 호출하는 `raw.githubusercontent.com`은 검증 없이 호출됨.
3. **가격 이상치 미감지**: 폴백 CSV 데이터에 오염된 가격(예: 삼성전자가 1원으로 기재)이 들어와도 그대로 DB에 저장됨. 전일 대비 ±30% 초과 등 이상치 필터 없음.
4. **B128 HTTP**: `B128_URL`이 `http://data.krx.co.kr/...`로 평문 HTTP. 최근 거래일 메타데이터이므로 실질 위험은 낮지만, MITM 가능성 존재.

현재 MVP 단계에서는 종가 미수집 → `unpricedCount` 증가로 graceful degradation 되므로 서비스 중단 위험은 낮음. 그러나 오염된 데이터가 수집되면 `totalEvalAmount`·`totalPnl`이 잘못 계산되어 사용자에게 잘못된 수익률을 보여줄 수 있어 **투자자 피해 가능성**이 있음.

## 요구사항

### R1 — 가격 이상치 필터 (우선순위: High)
- [ ] `fetchClosePricesFromGithubCache()`에서 파싱된 각 종목 가격에 이상치 필터 적용
  - 조건: `closePrice <= 0` → 스킵 (음수·0원 가격은 명백한 오염)
  - 조건: 전일 `stocks.close_price`와 비교해 ±50% 초과 시 WARN 로그 후 스킵 (DB 조회 비용이 있으므로 선택적 구현)
  - 최소 구현: `closePrice <= 0` 필터 + 1원 미만(`< 1`) 필터로도 대부분 오염 데이터 차단 가능
- [ ] KRX 직접 응답(`fetchClosePricesFromKrx()`)에도 동일 `closePrice <= 0` 필터 적용
- [ ] 필터링된 건수를 WARN 로그로 기록 (`{}건 비정상 가격 스킵`)

### R2 — HostWhitelist GitHub URL 등록 검토 (우선순위: Medium)
- [ ] `HostWhitelist.PROD_ALLOWED`에 `raw.githubusercontent.com` 추가 여부 의사결정
  - **Option A**: 추가 → SSRF 방어 완전성, GitHub 도메인 전체 허용(광범위) 트레이드오프
  - **Option B**: 추가 안 함 → 현 상태 유지(externalRestClient는 화이트리스트 미적용으로 문서화만)
  - MVP 판단: Option B 유지하되, `KrxClient` 주석에 "externalRestClient 호출 URL은 상수로 고정 — 동적 입력 절대 금지" 명시
- [ ] `externalRestClient`가 사용자 입력·환경변수 값을 호출하지 않음을 코드 주석으로 명시

### R3 — 대체 종가 소스 중장기 조사 (우선순위: Low — 중장기)
- [ ] KRX 공식 HTTPS 직접 접근 가능 여부 재조사 (현재 B128은 HTTP)
- [ ] 네이버 금융 비공식 API 조사 (`finance.naver.com/item/main.naver?code=XXXXXX`)
- [ ] 한국거래소 공식 공개 API (`openapi.krx.co.kr` — 신청 필요) 조사
- [ ] FinanceDataReader PyPI 패키지를 Python scripts/ 레이어에서 사용하는 방안 (scripts/data_collection/ 영역)
- → 이 항목은 **Stage 5(재무/업황) 착수 전 별도 dc-plan**으로 재검토

## 영향 범위 (조사 결과)

- 영향 레이어: backend(infrastructure/krx, shared/util)
- 영향 파일:
  - `backend/src/main/java/com/dartcommons/infrastructure/krx/KrxClient.java` — 이상치 필터 추가
  - `backend/src/main/java/com/dartcommons/shared/util/HostWhitelist.java` — GitHub URL 등록 여부 결정(R2 Option A 선택 시)
- DB 변경: 없음
- 외부 계약: 없음

## 관련 패턴 / 과거 사례

- `SecretMasker.mask()`: KrxClient에서 예외 메시지 마스킹에 이미 사용 중 — 가격 로그에도 동일 패턴 적용
- `HostWhitelist.verify()`: `KrxClient` 생성자·`DartClient` 생성자에 적용 — externalRestClient는 현재 제외
- 이상치 필터 선례: `fetchClosePricesFromKrx()`의 `stockCode.length() != 6` 필터, `NumberFormatException` 스킵 — 동일 방어 레이어에서 `closePrice <= 0` 추가

## 리스크 / 법적 검토

- **투자자 피해**: 오염된 종가로 잘못된 평가 손익(total_pnl)이 계산되어 사용자에게 표시될 경우 자본시장법 §178(부정행위 금지) 인접 — 정확한 데이터 출처 표기 + 면책 조항 필수 (이미 PortfolioSummaryResponse에 주석 존재)
- **공급망 공격**: 외부 레포 CSV 변조 → 이상치 필터(R1)로 1차 방어. 근본 해결은 KRX 직접 접근(R3).
- **GitHub API Rate Limit**: `raw.githubusercontent.com`은 unauthenticated 접근 시간당 60요청 제한 없음(콘텐츠 CDN). 일 1회 배치이므로 한도 문제 없음.

## 권장 구현 방향

### 즉시 구현 (R1 — 이번 wave에서 처리 가능)

`KrxClient.java`에 가격 이상치 필터 헬퍼 추가:

```java
/** 가격 이상치 방어 — 0원 이하 or 1원 미만은 CSV 오염 데이터로 판단하고 스킵. */
private boolean isValidPrice(BigDecimal price) {
    // 1원 미만: 상장 종목 최저가가 1원 — 그 미만이면 명백한 데이터 오류
    return price != null && price.compareTo(BigDecimal.ONE) >= 0;
}
```

`fetchClosePricesFromGithubCache()` 루프 내:
```java
BigDecimal price = new BigDecimal(rawPrice);
if (!isValidPrice(price)) {
    log.warn("GitHub cache 비정상 가격 스킵 — stockCode={}, price={}", stockCode, rawPrice);
    continue;
}
result.put(stockCode, new StockCloseInfo(price, date));
```

`fetchClosePricesFromKrx()` 루프 내도 동일 적용.

### 중장기 (R3 — 별도 dc-plan)

Stage 5 착수 전, 공식 KRX OpenAPI(`openapi.krx.co.kr`) 신청·검토 후 재설계.
`StockPriceProvider` seam이 격리하므로 `KrxClient` 교체 시 `PortfolioService` 무수정.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
