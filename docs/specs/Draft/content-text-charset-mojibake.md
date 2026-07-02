---
type: spec
status: Draft
created: 2026-07-03
updated: 2026-07-03
---

# 공시 본문 charset 오디코딩(mojibake) 수정 Spec

> 상태: **Draft** (dc-plan 생성)

## 배경 / 목적

`DartDocumentParser`가 DART 문서 바이트를 문자열로 디코딩할 때 **charset을 오판**해 본문(content_text)에 mojibake(깨진 문자 `�`, "異���利�沅�" 등)가 저장된다. 현재 감지 로직은 **XML 인코딩 선언 정규식 → 없으면 EUC-KR 기본**뿐이라, 선언이 없거나 실제 인코딩이 다른(UTF-8/혼합/일본어 포함) 문서를 잘못 디코딩한다.

**실측(2026-07-03)**: 93738 크래프톤 취득결정(ADK홀딩스 — 일본 법인, 본문에 혼합 문자)의 content_text가 "痍⑤��湲���"로 저장 → `stage2-body-in-prompt` 재분석 시 gemma가 이 깨진 문자를 그대로 key_points에 에코. **전체 content_text 중 24,389건(~36%)** 에 replacement char(`�`) 포함 — 광범위.

`stage2-body-in-prompt` 이후 본문이 LLM 분석에 직접 들어가므로, mojibake는 **요인/해설 품질을 직접 훼손**한다(목업 카드에 깨진 글자 노출). 목적: charset 감지를 정밀화해 오디코딩을 없애고, 기존 손상 문서를 재수집한다.

- 페르소나: A·E(투자자) — 본문 기반 해설에 깨진 글자가 노출되는 문제 해소.
- BM 티어: 무관(데이터 품질 인프라).

## 요구사항

- [ ] `DartDocumentParser` charset 감지 정밀화 — BOM 검사 + 후보 charset(UTF-8/EUC-KR/MS949) 시도 후 **replacement char 최소** 기준 선택(또는 감지 라이브러리 도입)
- [ ] 인코딩 선언이 있어도 실제와 불일치 시 보정(선언 신뢰 후 검증)
- [ ] 신규 수집부터 mojibake 미발생 검증 (샘플 문서 디코딩 테스트)
- [ ] 기존 손상 content_text(~24k건) **재수집** 전략 — content 백필 재실행 범위/식별
- [ ] 회귀 방지 단위 테스트 — UTF-8/EUC-KR/MS949/선언없음/BOM 케이스 정확 디코딩

## 영향 범위 (조사 결과)

- 영향 레이어: **backend(infrastructure/dart + disclosure)**. FE·응답 계약 무변경.
- 영향 파일:
  - 수정: `infrastructure/dart/DartDocumentParser.java`(detectCharset — BOM + 다중 후보 + 최소 �)
  - 참고: `infrastructure/dart/DartDocumentClient.java`(zip 추출 — 변경 없음), `disclosure/services/DisclosureContentService.java`(저장/절삭 — 변경 없음)
  - 테스트: `infrastructure/dart/DartDocumentParserTest.java`(charset 케이스)
- **DB 변경**: 없음(스키마). 단 **content_text 데이터 재수집 필요**(손상 24k건) — 기존 content 백필 잡(V25) 재실행.
- **외부 계약**: DART 문서 응답 파싱만 정밀화. 계약 불변.
- **의존성**: charset 감지 라이브러리(juniversalchardet 등) 도입 시 build.gradle 추가 — 또는 무의존 휴리스틱(권장).

## 관련 패턴 / 과거 사례

- `DartDocumentParser` 주석: *"인코딩 선언 없으면 EUC-KR 기본 적용(DART 구 공시 특성)"* — 이 기본값이 최신/혼합 인코딩 문서에서 오판 유발.
- `stage2-body-in-prompt`(2026-07-03)가 본문을 LLM에 투입하며 이 데이터 품질 문제를 표면화 — 직접 후속.
- 현재 charset 감지 라이브러리 없음(수동) — build.gradle 확인 결과.

## 리스크 / 법적 검토

- **환각 아님, 데이터 품질**: mojibake는 LLM 환각이 아니라 입력 데이터 손상 — 원본 인용 필드(수치·회사명, CLAUDE.md §4)가 깨져 투자자에게 잘못 노출될 수 있음(간접적 §11 리스크).
- **재수집 비용**: 24k 문서 재fetch = DART API 호출(일일 한도·throttle) + 재파싱 + 재분석 연쇄. content 백필 잡 재실행으로 분할.
- **charset 감지 오버엔지니어링 경계**: 라이브러리 도입 vs 휴리스틱 — 무의존 "후보 디코딩 후 최소 �" 휴리스틱이 MVP에 충분할 가능성(확인 필요).

## 권장 구현 방향

- **접근 A(권장, 무의존 휴리스틱)**: BOM 검사(UTF-8/UTF-16) → 인코딩 선언 존중하되, 디코딩 결과의 replacement char 비율이 임계 초과면 후보 charset(UTF-8·MS949·EUC-KR)로 재시도 → `�` 최소인 결과 선택. 라이브러리 무추가.
- **접근 B**: juniversalchardet/ICU4J 도입 — 감지 정확도↑이나 의존성·크기. 휴리스틱으로 부족할 때 승급.
- **재수집**: 파서 수정 후 `content_text LIKE '%�%'` 대상 id로 content 백필 잡 재실행(idFrom/idTo 배치). 재수집 후 해당 공시 재분석(stage2-body-in-prompt 재분석과 병합).

### 확인 필요
- 무의존 휴리스틱만으로 24k 케이스 대부분 복구되는지(샘플 실측)
- 재수집 범위·DART 호출 한도 내 배치 크기
- 일부 문서가 원천적으로 혼합 인코딩(한/일 혼재)이라 완전 복구 불가한 비율

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
