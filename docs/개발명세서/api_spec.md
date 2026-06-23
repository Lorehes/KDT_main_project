---
type: doc
status: draft
created: 2026-05-30
updated: 2026-05-30
---

# API 명세 — 자체 REST API + 외부 OpenAPI (초안)

> **근거**: [[DART공시통역_통합기획서]] §4 서비스 흐름 · §5.1 시스템 구성도(REST API 박스) · §5.2 백엔드 스택(springdoc-openapi) · §9 알림 · §10 인증 · §11 법적 제약.
> **연계**: 데이터 모델은 [[db_schema]], 모듈/시퀀스/큐는 [[feature_structure]], 화면(클라이언트)은 [[design_structure]].
> **규칙**: 외부 키는 환경변수로만 주입([[CLAUDE]] §4·§7). LLM 분석 응답은 **신뢰도(confidence) 필수 + 면책 문구 동반**([[CLAUDE]] §6-6). **투자 권유 표현 금지**(자본시장법, 통합기획서 §11.1).
>
> 본 문서는 **초안**이다. 서비스 흐름·외부 API 정책 변동 시 함께 갱신한다. 자체 API는 `springdoc-openapi`가 생성하는 런타임 Swagger(`/swagger-ui`)가 최종 SSOT이며, 본 문서는 설계 합의를 기록한다.

---

## 1. 자체 REST API 공통 규약

### 1.1 기본

| 항목 | 값 |
|------|------|
| Base URL (local) | `http://localhost:8080` |
| 버저닝 | URI 접두사 `/api/v1` (§5.1 명시) |
| 직렬화 | JSON (UTF-8). 요청/응답 `Content-Type: application/json` |
| 시각 표기 | ISO-8601 `OffsetDateTime` (`2026-05-30T09:00:00+09:00`) |
| 문서 | `springdoc-openapi` → `/swagger-ui`, `/v3/api-docs` |
| 네이밍 | 경로·쿼리 `snake_case` 미사용, 리소스는 복수형 명사 (`/portfolios`) |

### 1.2 인증 (Spring Security + JWT)

- 인증 방식: **Bearer JWT** (`Authorization: Bearer <access_token>`).
- 토큰 쌍: `access_token`(단기, 기본 30분) + `refresh_token`(장기, 기본 14일). 갱신은 `POST /api/v1/auth/refresh`.
- OAuth2(Kakao/Google/Naver)는 인가 코드 → 백엔드 토큰 교환 후 동일한 자체 JWT를 발급한다(클라이언트는 이후 자체 JWT만 사용).
- 보호 리소스 미인증 접근 → `401`. 권한(티어) 부족 → `403`.

| 접근 등급 | 의미 |
|------|------|
| `PUBLIC` | 토큰 불필요 (랜딩·가입·로그인·종목 검색 일부) |
| `USER` | 유효한 access token 필요 |
| `TIER:PRO` / `TIER:PREMIUM` | 해당 등급 이상 (Stage 3~5 분석 필드 등) |

### 1.3 공통 에러 포맷

모든 4xx/5xx는 단일 스키마로 응답한다(클라이언트 분기 단순화).

```jsonc
{
  "timestamp": "2026-05-30T09:00:00+09:00",
  "status": 400,
  "code": "VALIDATION_ERROR",      // 기계 판독용 안정 코드
  "message": "이메일 형식이 올바르지 않습니다.", // 사용자 노출 가능 메시지(한국어)
  "path": "/api/v1/auth/signup",
  "errors": [                       // 필드 검증 실패 시에만
    { "field": "email", "reason": "must be a well-formed email address" }
  ]
}
```

| HTTP | code 예시 | 상황 |
|------|------|------|
| 400 | `VALIDATION_ERROR` | 요청 본문/파라미터 검증 실패 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `TIER_FORBIDDEN` / `FORBIDDEN` | 등급/권한 부족 |
| 404 | `RESOURCE_NOT_FOUND` | 리소스 없음 |
| 409 | `DUPLICATE_RESOURCE` | 유니크 충돌(이메일·종목 중복 등) |
| 422 | `BUSINESS_RULE_VIOLATION` | Free 종목 3개 초과 등 정책 위반 |
| 429 | `RATE_LIMITED` | 호출 한도 초과 |
| 500 | `INTERNAL_ERROR` | 서버 오류(상세 미노출) |

### 1.4 페이지네이션 / 정렬

목록 API는 페이지 기반.

| 파라미터 | 기본 | 설명 |
|------|------|------|
| `page` | 0 | 0-base 페이지 인덱스 |
| `size` | 20 | 페이지 크기(최대 100) |
| `sort` | 리소스별 | `field,asc|desc` (예: `rcept_dt,desc`) |

응답 envelope:

```jsonc
{
  "content": [ /* ... */ ],
  "page": { "number": 0, "size": 20, "total_elements": 137, "total_pages": 7 }
}
```

### 1.5 멱등성 / 레이트리밋

- 외부에서 트리거되는 쓰기는 없으나, 재시도 안전을 위해 생성 API는 자연 키(이메일·`(user, stock)`)로 충돌을 `409`로 차단.
- 인증·검색 등 남용 가능 엔드포인트는 IP + 사용자 기준 레이트리밋(임계치 운영값, [[feature_structure]] 참조). 초과 시 `429` + `Retry-After`.

---

## 2. 자체 REST API — 도메인별 엔드포인트

> 도메인 모듈 경계는 [[CLAUDE]] §3-2 (`user` · `portfolio` · `disclosure` · `analysis` · `notification`). 컨트롤러는 각 모듈 `controllers/`.

### 2.1 인증 · 사용자 (`user`)

| Method | Path | 접근 | 설명 |
|--------|------|------|------|
| `POST` | `/api/v1/auth/signup` | PUBLIC | 이메일 회원가입 (§4.1) |
| `POST` | `/api/v1/auth/login` | PUBLIC | 이메일 로그인 → JWT 발급 |
| `GET`  | `/api/v1/auth/oauth/{provider}/url` | PUBLIC | OAuth 인가 URL 발급 (`provider`=kakao/google/naver) |
| `POST` | `/api/v1/auth/oauth/{provider}/callback` | PUBLIC | 인가 코드 → 자체 JWT 발급 |
| `POST` | `/api/v1/auth/refresh` | PUBLIC | refresh_token → 새 access_token |
| `POST` | `/api/v1/auth/logout` | USER | refresh_token 무효화 |
| `GET`  | `/api/v1/users/me` | USER | 내 프로필·티어·알림설정 조회 |
| `PATCH`| `/api/v1/users/me` | USER | 닉네임·투자경험 수정 |
| `POST` | `/api/v1/users/me/phone/verify` | USER | 휴대폰 인증(알림톡 사용 시, §4.1[6]) |
| `DELETE`| `/api/v1/users/me` | USER | 회원 탈퇴(soft delete `deleted_at`) |
| `POST` | `/api/v1/consents` | USER | 동의 기록 INSERT (정책 버전별, §11.1) |
| `GET`  | `/api/v1/consents/status` | USER | 동의 항목별 최신 상태 조회 |

**`POST /api/v1/auth/signup` 요청**

```jsonc
{
  "email": "user@example.com",
  "password": "••••••••",          // BCrypt/Argon2 해시 저장(평문 미저장)
  "nickname": "투자초보",
  "consents": [                     // 필수: TERMS·PRIVACY / 선택: MARKETING·DISCLAIMER
    { "consent_type": "TERMS",   "agreed": true, "policy_version": "v1.0" },
    { "consent_type": "PRIVACY", "agreed": true, "policy_version": "v1.0" },
    { "consent_type": "MARKETING", "agreed": false, "policy_version": "v1.0" }
  ]
}
```

> 동의는 가입과 원자적으로 처리되어 `users.*_agreed_at`(최초 시각)과 `consent_logs`(INSERT-only 이력, SSOT)에 동시 기록된다([[db_schema]] §3.1·§3.7). 필수 동의 누락 시 `422 BUSINESS_RULE_VIOLATION`.

**`GET /api/v1/users/me` 응답**

```jsonc
{
  "id": 1,
  "email": "user@example.com",
  "nickname": "투자초보",
  "tier": "FREE",                   // FREE | PRO | PREMIUM
  "tier_expires_at": null,
  "phone_verified": false,
  "notify": {                       // db_schema V7
    "channel": "KAKAO",             // KAKAO | TELEGRAM | EMAIL
    "enabled": true,
    "frequency": "INSTANT",         // INSTANT | DAILY_1 | DAILY_2 | WEEKLY
    "type_filter": "ALL",           // POSITIVE_ONLY | NEGATIVE_ONLY | ALL
    "off_hours_allowed": true
  }
}
```

> 휴대폰 번호는 `phone_number_enc`(AES-256-GCM)로만 저장하며 응답에 평문 노출 금지 — `phone_verified` 불리언만 반환([[CLAUDE]] §7).

### 2.2 종목 · 보유 (`portfolio`)

| Method | Path | 접근 | 설명 |
|--------|------|------|------|
| `GET`  | `/api/v1/stocks/search?q=삼성` | PUBLIC | 종목 자동완성 검색(이름/코드) |
| `GET`  | `/api/v1/portfolios` | USER | 내 보유 종목 목록 |
| `POST` | `/api/v1/portfolios` | USER | 보유 종목 등록(매수가/수량 선택) |
| `PATCH`| `/api/v1/portfolios/{id}` | USER | 매수가/수량/메모 수정 |
| `DELETE`| `/api/v1/portfolios/{id}` | USER | 보유 종목 삭제 |
| `POST` | `/api/v1/portfolios/import` | USER | 거래내역 CSV 업로드 → 일괄 등록(§4.2) |
| `POST` | `/api/v1/stocks/coverage-requests` | USER | 미커버 종목 추가 요청(§3.1) |

**`POST /api/v1/portfolios` 요청 / 응답**

```jsonc
// 요청
{ "stock_code": "005930", "avg_buy_price": 71000, "quantity": 10, "memo": "장기" }

// 응답 (매수가/수량은 평문 반환하되 저장은 암호화; 손익 계산은 복호화 후 앱 계층)
{
  "id": 12, "stock_code": "005930", "corp_name": "삼성전자",
  "avg_buy_price": 71000, "quantity": 10, "memo": "장기",
  "created_at": "2026-05-30T09:00:00+09:00"
}
```

> 규칙: **Free 등급 3종목 초과 시 `422 BUSINESS_RULE_VIOLATION`**(§8.1, [[db_schema]] §3.3). 동일 `(user, stock_code)` 재등록은 `409 DUPLICATE_RESOURCE`(`uq_portfolio_user_stock`). `avg_buy_price`/`quantity`는 **AES-256-GCM 암호화 저장**, 평문 로깅 금지([[CLAUDE]] §7).

### 2.3 공시 (`disclosure`)

| Method | Path | 접근 | 설명 |
|--------|------|------|------|
| `GET`  | `/api/v1/disclosures` | USER | 공시 목록(내 보유 종목 필터 기본) |
| `GET`  | `/api/v1/disclosures/{id}` | USER | 공시 상세(원문 메타 + 분석 요약) |

**목록 쿼리 파라미터**

| 파라미터 | 예시 | 설명 |
|------|------|------|
| `scope` | `portfolio`(기본) / `all` | 보유 종목만 / 전체 커버 종목 |
| `stock_code` | `005930` | 특정 종목 |
| `sentiment` | `POSITIVE` | 분석 결과 필터 |
| `from` / `to` | `2026-05-01` | 접수일자 범위 |
| `sort` | `rcept_dt,desc` | 정렬 |

> 공시 원문 인용 필드(`corp_name`·`report_nm`·수치·날짜)는 **룰 기반(Stage 1) 원본 그대로** 반환하며 LLM 변형 금지([[CLAUDE]] §4, [[db_schema]] §3.4). 대용량 원문은 `attachment_url`(DART 원문 링크/S3 참조)로 제공.

### 2.4 분석 (`analysis`)

| Method | Path | 접근 | 설명 |
|--------|------|------|------|
| `GET`  | `/api/v1/disclosures/{id}/analysis` | USER | 공시 분석 결과(티어별 필드 차등) |
| `POST` | `/api/v1/analyses/{analysisId}/feedback` | USER | 분석 피드백(유용함/부정확함, §4.6) |

**`GET /api/v1/disclosures/{id}/analysis` 응답**

```jsonc
{
  "analysis_id": 88,
  "disclosure_id": 41,
  "sentiment": "NEGATIVE",          // POSITIVE | NEUTRAL | NEGATIVE
  "confidence": 0.62,               // 0~1 필수
  "is_withheld": false,             // true면 "판단 보류" 표시(신뢰도 낮음)
  "summary": "전환사채 1,000억 발행으로 주식 추가 발행 가능성이 있어...", // 3줄
  "stage_reached": 4,               // 1~5

  // TIER:PRO 이상에서만 채워짐 (Stage 3~4)
  "expected_reaction": "DOWN",      // UP | FLAT | DOWN
  "rationale": "동일 회사 동일 유형 공시 시 평균 5일 -6.3% 반응(과거 4건)",
  "similar_disclosures": [
    { "rcept_no": "20250...", "corp_name": "...", "rcept_dt": "2025-...",
      "price_reaction_5d_pct": -6.3 }
  ],

  // TIER:PREMIUM 에서만 (Stage 5)
  "financial_context": { /* 재무/업황 요약 */ },

  "disclaimer": "본 분석은 정보 제공용이며 투자 자문/권유가 아닙니다. AI 분석은 부정확할 수 있으며 투자 책임은 이용자에게 있습니다.",
  "report_inaccuracy_path": "/api/v1/analyses/88/feedback",
  "created_at": "2026-05-30T09:01:12+09:00"
}
```

> **불변 규칙**([[CLAUDE]] §6-6·§7): `confidence` 필드 항상 포함, `is_withheld=true`면 호재/악재 단정 대신 "판단 보류" 표시. **모든 분석 응답에 `disclaimer`와 신고 경로(`report_inaccuracy_path`) 동반.** 티어 미달 사용자에게는 상위 Stage 필드를 응답에서 **제외**(노출 후 마스킹 금지)하고, 화면은 업셀 CTA로 처리([[design_structure]]).

> **Stage 3·5 미구현 정책 (2026-06-23 기준)**: `similar_disclosures` (Stage 3 RAG)·`financial_context` (Stage 5) 필드는 **구현 완료 전까지 모든 티어에서 `null` 반환**하며 `@JsonInclude(NON_NULL)`에 의해 JSON 직렬화에서 제외됨. FE는 이 필드가 `null`일 때 Pro+·Premium 업셀 CTA로 처리 중(의도적 설계). Stage 3·5 Spec 완료 시 `AnalysisResponse.from()` 내 `TODO Stage-3/5` 주석 교체 지점 참조.

**`POST /api/v1/analyses/{analysisId}/feedback` 요청**

```jsonc
{ "verdict": "INACCURATE", "reason": "계약 상대가 잘못 요약됨" } // verdict: USEFUL | INACCURATE
```

> 동일 `(user, analysis)` 재투표는 **UPDATE**(`uq_feedbacks_user_analysis`, [[db_schema]] §3.8). `reason`은 앱 계층에서 길이 캡(권장 ≤2000자).

### 2.5 알림 (`notification`)

| Method | Path | 접근 | 설명 |
|--------|------|------|------|
| `GET`  | `/api/v1/notifications` | USER | 내 알림 발송 이력 |
| `GET`  | `/api/v1/notifications/settings` | USER | 알림 설정 조회 |
| `PUT`  | `/api/v1/notifications/settings` | USER | 알림 설정 갱신(채널/빈도/필터/거래시간외, §4.3·§9.2) |
| `POST` | `/api/v1/notifications/test` | USER | 설정 검증용 테스트 발송 |

**`PUT /api/v1/notifications/settings` 요청**

```jsonc
{
  "channel": "TELEGRAM",            // KAKAO | TELEGRAM | EMAIL
  "enabled": true,
  "frequency": "DAILY_1",           // INSTANT | DAILY_1 | DAILY_2 | WEEKLY
  "type_filter": "NEGATIVE_ONLY",   // POSITIVE_ONLY | NEGATIVE_ONLY | ALL
  "off_hours_allowed": false
}
```

> 발송 자체는 시스템 자동 흐름(§4.4)이며 외부 API로 트리거하지 않는다. 발송 이력은 `(user, disclosure, channel)` 멱등(`uq_notification_dedup`, [[db_schema]] §3.6).

### 2.6 요금제 (`user` / 정적)

| Method | Path | 접근 | 설명 |
|--------|------|------|------|
| `GET`  | `/api/v1/pricing/plans` | PUBLIC | 티어별 가격·기능 목록(§8.1) |

> MVP는 실제 결제 미연동(통합기획서 §6 결정 — Pro 화면 UI만). 결제 콜백 엔드포인트는 사업화 단계 후속.

---

## 3. 외부 OpenAPI 소비 명세

> 모든 외부 호출은 `infrastructure/`의 WebClient로 격리하고 **타임아웃 + 지수 백오프 재시도**를 설정([[CLAUDE]] §4). 키는 환경변수로만 주입.

### 3.1 DART OpenAPI (공시) — `DART_API_KEY`

기준 URL: `https://opendart.fss.or.kr/api/`. 인증 파라미터: `crtfc_key`.

| 용도 | 엔드포인트 | 핵심 파라미터 | 호출 주기 |
|------|------|------|------|
| 신규 공시 폴링 | `list.json` | `crtfc_key`, `bgn_de`, `end_de`, `page_no`, `page_count`, `pblntf_ty`(공시유형), `corp_code`(선택) | **@Scheduled 1분** |
| 과거 공시 백필 | `list.json` | 동일 (`bgn_de`~`end_de` 90일 청크 분할) | 운영자 트리거 1회 `POST /admin/disclosures/backfill` |
| 고유번호 매핑 | `corpCode.xml` | `crtfc_key` (zip 다운로드 → StAX 스트리밍 파싱) | 분기/초기 1회 (`StockMasterSyncJob` 또는 `seed_stocks.py`) |
| 공시 원문 | `document.xml` | `crtfc_key`, `rcept_no` | 신규 공시 시 |
| 기업개황 | `company.json` | `crtfc_key`, `corp_code` | 종목 등록/갱신 |
| 정기보고서 재무(Stage 5) | `fnlttSinglAcnt.json` 등 | `crtfc_key`, `corp_code`, `bsns_year`, `reprt_code` | 분기 배치 |

**폴링 로직 핵심**

- `list.json` 응답의 **`rcept_no`(접수번호 14자리)를 멱등 키**로 사용 → 이미 적재된 번호는 스킵(중복 발송 금지, [[CLAUDE]] §4).
- 응답 `status` 필드 처리: `000`(정상) 외(`013` 데이터없음, `020` 키오류, `100` 파라미터오류, `800/900` 시스템) 는 코드별 분기 + 로깅. `020`/`800`은 즉시 알림(키/장애).
- 회사명·제목·수치·날짜는 **원본 그대로 저장**(Stage 1), LLM 변형 금지.

```jsonc
// list.json 응답(요지) — disclosures 테이블 매핑
{
  "status": "000", "message": "정상",
  "list": [
    { "rcept_no": "20260530000123", "corp_code": "00126380",
      "corp_name": "삼성전자", "stock_code": "005930",
      "report_nm": "단일판매ㆍ공급계약체결", "rcept_dt": "20260530" }
  ]
}
```

> `pblntf_ty`(공시 유형) 코드 화이트리스트로 커버 대상 1차 필터([[feature_structure]] Stage 1). 종목 커버리지(코스피200+코스닥150, §3.1)는 `stocks` 마스터 조인으로 2차 필터.

### 3.2 KRX 정보데이터시스템 (`data.krx.co.kr`) — 공개 데이터(인증 불필요)

기준: KRX 정보데이터시스템(`data.krx.co.kr`). 공개 페이지의 데이터 셀 API(`bldAttendant/getJsonData.cmd`)를 사용. `KRX_API_KEY`는 일부 유료 API 한정이며 공개 데이터는 미필요.

| 용도 | bld(데이터 ID) | 폼 파라미터 | 호출 주기 | 호출 위치 |
|------|---------------|-----------|---------|---------|
| 종목 기본정보(전종목 market/sector) | `dbms/MDC/STAT/standard/MDCSTAT01901` | `mktId=ALL`, `trdDd=YYYYMMDD` | 분기 1회 | `KrxClient.fetchAllBasicInfo()` ← `StockMasterSyncJob` |
| 인덱스 구성종목(코스피200/코스닥150) | (pykrx `get_index_portfolio_deposit_file`) | 인덱스 코드 KOSPI200=1028, KOSDAQ150=2203 | 분기 1회(리밸런싱) | `scripts/data_collection/seed_stocks.py` |
| 일별 시세(종가/등락률) | (미확정 — 후속 Spec) | — | 일 1회 배치 | (미구현) |
| 공시 후 5일 반응 | 일별 시세 시계열에서 산출(Stage 3 결합) | — | 분석 시 조회 | (미구현) |

> **검증 필요**: KRX 공개 데이터 API는 비공식 인터페이스로 사전 공지 없이 변경 가능. `KrxClient` 구현은 pykrx(검증된 라이브러리) 패턴 기반이지만, 운영 환경에서 1회 실측 검증 후 응답 필드명 변동 시 `KrxClient.parseResponse()`의 키만 갱신. 시드 산출(인덱스 구성종목)은 pykrx에 위임해 안정성 확보.

**호출 형식**:
```
POST http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd
Content-Type: application/x-www-form-urlencoded

bld=dbms/MDC/STAT/standard/MDCSTAT01901&mktId=ALL&trdDd=20260601&share=1&money=1&csvxls_isNo=false
```

**예상 응답(검증 필요)**:
```json
{
  "OutBlock_1": [
    { "ISU_SRT_CD": "005930", "ISU_ABBRV": "삼성전자", "MKT_NM": "KOSPI", "IDX_IND_NM": "전기·전자", ... },
    ...
  ]
}
```

> 주가 시계열은 핵심 5테이블 밖이며, 필요 시 `stock_prices` 보조 테이블 추가를 검토한다([[db_schema]] §4 주석). Stage 3의 "과거 5일 주가 반응"은 KRX 배치 적재분에서 계산.

### 3.3 공공 API (업황, Stage 5) — `PUBLIC_DATA_API_KEY`

기준: 공공데이터포털(`data.go.kr`) 산업/업황 통계. **구체 서비스는 미확정(후보 단계).** 호출 주기 **월 1회 배치**. Premium Stage 5의 업황 맥락 결합에만 사용.

---

## 4. 보안 · 컴플라이언스 체크 (이 API 기준)

- [ ] 모든 `USER` 리소스는 JWT 검증 + 소유자 스코프 강제(타 사용자 `portfolio`/`feedback` 접근 차단 — IDOR 방지)
- [ ] 매수가/수량/휴대폰은 응답에서 평문 정책 준수, **저장은 AES-256-GCM**, 로그 마스킹([[CLAUDE]] §7)
- [ ] 분석 응답에 `confidence` + `disclaimer` + 신고 경로 **항상 포함**, 티어 미달 상위 Stage 필드는 응답 제외
- [ ] **투자 권유 표현 금지** — 요약/근거 카피 검수(LLM 프롬프트 가드 + 응답 후처리, §11.1)
- [ ] 외부 호출 타임아웃·지수 백오프·`status` 코드 분기(DART), 키는 환경변수
- [ ] 인증/검색 엔드포인트 레이트리밋(`429` + `Retry-After`)
- [ ] 에러 응답에 스택트레이스·내부 식별자 미노출(`500`은 일반 메시지)

---

## 관련 문서

- [[db_schema]] — 응답 필드 ↔ 테이블 매핑(users·portfolios·disclosures·analysis_results·notifications·consent_logs·feedbacks)
- [[feature_structure]] — 폴링/분석/발송 시퀀스·큐·재시도·스케줄러
- [[design_structure]] — 클라이언트 라우트 ↔ API 소비 매핑
- [[DART공시통역_통합기획서]] §4·§5.1·§9·§10·§11 · [[CLAUDE]] §4(외부 API)·§6-6(검증)·§7(금지)
