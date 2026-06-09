---
type: doc
status: done
created: 2026-06-09
updated: 2026-06-09
---

# FE-BE 정합성 불일치 사후 분석 (Post-mortem)

> 작성 배경: `frontend-full-ui-implementation` Done 직후 7개 축 정합성 감사에서 P0 4건·P1 15건·P2 7건 발견.
> 이 문서는 **왜 불일치가 발생했는지**를 코드·프로세스·기획 세 관점에서 분석하고 재발 방지 대책을 기술한다.

---

## 1. 발견된 문제 요약

| 심각도 | 건수 | 대표 사례 |
|--------|------|------|
| **P0** | 4건 | BE Set-Cookie 미발급·SignupRequest 스키마 불일치·useUpdatePortfolio 미구현·401 인터셉터 없음 |
| **P1** | 15건 | AuthUser.notify 필드 누락·Portfolio.notify_enabled 런타임 undefined·SentimentBadge 토큰 미정의 등 |
| **P2** | 7건 | next.config.ts 보안 헤더 없음·hex 색상 하드코딩·IA 계층 오류 등 |

---

## 2. 근본 원인 분석

### 2-1. 코드 관점 — "Spec 주도 구현"의 함정

**문제**: FE는 `api_spec.md`를 보고 구현했으나, BE는 명세와 **다른 방식으로 구현**됐다.

#### 사례 1: SignupRequest 스키마 불일치 (P0)

```
api_spec.md (명세):
  consents: [{consent_type: "TERMS", agreed: true, policy_version: "v1.0"}, ...]

BE 실제 구현 (SignupRequest.java):
  boolean termsAgreed
  boolean privacyAgreed
  boolean disclaimerAgreed
  boolean marketingAgreed

FE 구현 (lib/api/auth.ts):
  consents: ConsentItem[]  ← 명세 기준 구현
```

**원인**: api_spec.md가 배열 구조를 명세했지만, BE 개발자는 플랫 boolean이 더 단순하다고 판단해 다르게 구현. 명세 업데이트 없이 BE만 변경. FE는 명세를 신뢰하고 구현 → **명세-구현 간 동기화 단절**.

#### 사례 2: httpOnly 쿠키 미발급 (P0)

```
FE middleware.ts:
  const session = request.cookies.get("dr_session");  // 쿠키 기대

BE AuthController.java:
  return authService.signup(request);  // JSON body만 반환, Set-Cookie 없음

AuthResponse.java 주석:
  // HttpOnly Cookie 방식으로 전환 시 Set-Cookie 헤더로 이동  ← 미구현 TODo
```

**원인**: BE `AuthResponse.java` 주석에 "Cookie 방식으로 전환 시"라는 조건부 계획이 있었으나, FE 미들웨어 설계 시 이를 **이미 구현된 것으로 가정**했다. 양측이 서로 상대방이 처리한다고 착각한 **책임 공백**.

#### 사례 3: Portfolio.notify_enabled 필드 허구 (P1)

```
FE Portfolio 인터페이스:
  notify_enabled: boolean  ← 존재하지 않는 필드

BE PortfolioResponse.java:
  id, stock_code, avg_buy_price, quantity, memo, created_at, updated_at
  ← notify_enabled 없음
```

**원인**: FE 설계 시 "알림 on/off가 포트폴리오마다 있을 것"이라는 **기능적 가정**으로 인터페이스를 선설계. BE 실제 응답 스키마를 확인하지 않고 타입을 작성.

---

### 2-2. 프로세스 관점 — 단계 간 검증 부재

#### 문제: "구현 후 검증" 패턴 없음

```
현재 프로세스:
  dc-plan → dc-tech-review → dc-implement → dc-push
                                           ↑
                               여기서 BE 실제 구현 확인 안 됨
```

FE 구현(dc-implement) 단계에서 BE 실제 구현 파일을 **읽지 않고** api_spec.md만 참고했다. api_spec.md는 **설계 합의 문서**이지 **구현 현황 문서**가 아니다. 이 둘을 동일시한 것이 핵심 오류다.

#### 문제: Wave 분리가 FE-BE 병렬화를 숨김

W1(FE 기반)~W7(FE 전체) 구현 동안 BE 상태를 한 번도 확인하지 않았다. Wave가 FE 내부 의존성으로만 분리되어 **BE와의 계약 검증 시점**이 없었다.

#### 문제: api_spec.md 업데이트 정책 미흡

```
현재: BE 구현 변경 → api_spec.md 미반영 (드리프트 발생)
필요: BE 구현 변경 → api_spec.md 즉시 동기화 (SSOT 유지)
```

`api_spec.md`가 SSOT로 선언되어 있으나, BE 구현이 명세와 달라졌을 때 업데이트하는 **의무 게이트**가 없었다. `/dc-push` 완료 시 api_spec.md와 실제 컨트롤러를 대조하는 단계가 누락됐다.

---

### 2-3. 기획 관점 — "약속"과 "구현" 사이의 거리

#### 문제: 상세 명세의 불완전성

api_spec.md §2.3(공시 상세)·§2.5(알림 이력)에 응답 JSON 예시가 없다. FE 개발자는 `Disclosure`, `Notification` 인터페이스를 **추론으로 작성**했다. 이는 다음을 야기했다:

- `corp_name`이 포트폴리오 응답에 있다고 가정 → 실제로 없음
- `notify_enabled`가 포트폴리오별로 있다고 가정 → 실제로 없음

**근본 원인**: 기획·설계 단계에서 "어떤 데이터가 어떤 형태로 오가는가"를 JSON 예시 수준까지 완성하지 않고 개발을 시작했다. 타입 계약이 느슨하면 구현은 반드시 갈린다.

#### 문제: 인증 방식 결정의 미확정

인증 토큰 전달 방식(JSON body vs httpOnly Cookie)이 api_spec.md에 "권장"으로만 기술되어 있고 **어느 시점에 Cookie로 전환할지** 결정되지 않았다. 이 미확정이:
- BE: JSON body로 구현 (간단한 방식)
- FE: Cookie를 기대 (보안 강화 방식)
- 결과: 둘 다 자기 방식으로 구현 → P0 충돌

---

## 3. 수정 내용 요약

### P0 수정 (4건)

| 문제 | 수정 방향 | 파일 |
|------|------|------|
| BE Set-Cookie 미발급 | Next.js API Route 브리지 (`/api/auth/session`) — FE 내에서 httpOnly 쿠키 설정 | `app/api/auth/session/route.ts` |
| SignupRequest 스키마 불일치 | FE를 BE 실제 구현(flat boolean)에 맞게 수정 | `app/(auth)/signup/terms/page.tsx`, `lib/api/auth.ts` |
| useUpdatePortfolio 미구현 | `PUT /portfolios/{id}` 훅 추가 (BE는 @PutMapping 사용) | `lib/api/portfolios.ts` |
| 401 인터셉터 없음 | client.ts에 refresh 재시도 로직 추가, `/api/auth/refresh` Route Handler 생성 | `lib/api/client.ts`, `app/api/auth/refresh/route.ts` |

### 수정 방향 결정: FE를 BE에 맞춤 (BE 변경 최소화)

P0 #2(SignupRequest)의 경우 두 가지 선택지가 있었다:
- **A. FE를 BE에 맞춤** — flat boolean 구조로 변경 (채택)
- **B. BE를 명세에 맞춤** — `List<ConsentItem> consents`로 변경

A를 채택한 이유: BE의 `ConsentService.recordSignupConsents(termsAgreed, ...)` 인터페이스가 이미 플랫 boolean을 전제하고 있어, BE 변경 시 ConsentService·ConsentLogRepository까지 연쇄 수정이 필요했다. FE 변경이 위험도·범위 면에서 훨씬 작았다.

P0 #1(쿠키)의 경우:
- **A. BE에서 Set-Cookie 발급** — 권장이나 Spring Security Cookie 설정이 복잡
- **B. FE Next.js Route Handler 브리지** — FE 내에서 해결 가능 (채택)

B를 채택한 이유: BE 변경 없이 FE만으로 해결 가능하고, 향후 BE가 Cookie를 직접 발급하도록 전환해도 Route Handler만 제거하면 된다.

---

## 4. 재발 방지 대책

### 단기 (즉시 적용)

1. **BE 구현 파일 직접 확인 필수** — FE dc-implement 시작 전 관련 BE Controller/DTO를 Read 도구로 직접 읽어 실제 필드명·타입·메서드를 확인한다. api_spec.md만 신뢰하지 않는다.

2. **응답 JSON 예시 필수** — api_spec.md에 응답 스키마를 추가할 때 JSON 예시(`// 응답`) 섹션을 필수로 포함한다. 예시 없이 타입만 기술하면 FE가 추론에 의존하게 된다.

3. **인증 방식 결정 문서화** — BE 스택이 변경될 때마다 토큰 전달 방식(body/cookie)을 api_spec.md에 명시적으로 갱신한다.

### 중기 (다음 Spec 단계)

4. **FE-BE 계약 테스트 추가** — `frontend-api-integration` Spec 구현 시 FE 타입과 BE 응답을 자동으로 대조하는 `pact` 또는 간단한 통합 테스트 추가.

5. **dc-implement에 BE 확인 체크리스트 추가** — FE Spec 구현 전 "관련 BE Controller/DTO를 읽었는가?" 게이트를 SKILL.md에 추가.

6. **api_spec.md 업데이트를 dc-push 게이트에 포함** — BE wave 완료 시 api_spec.md와 실제 컨트롤러 응답 비교 단계를 WORKLOG에 명시.

### 장기 (아키텍처)

7. **OpenAPI 자동 생성 활용** — BE springdoc-openapi가 이미 `/swagger-ui`·`/v3/api-docs`를 생성한다. FE `openapi-generator`로 타입을 자동 생성하면 드리프트를 원천 차단할 수 있다. 현재는 수동 타입 작성 — 향후 CI에서 OpenAPI JSON을 fetch해 FE 타입 자동 생성 파이프라인 구축 권장.

---

## 5. 타임라인 재구성

```
2026-06-09 오전: frontend-full-ui-implementation Spec 작성
                api_spec.md 기준으로 FE 타입 설계 (BE 실제 파일 미확인)
                
2026-06-09 낮:  W1~W7 구현 완료 (7개 Wave, 29 카드)
                각 Wave에서 BE 파일 미확인
                
2026-06-09 저녁: 정합성 감사 요청 → 7개 축 워크플로우 실행
                P0 4건 발견 (서비스 전체 작동 불가 수준)
                  - 미들웨어 상시 /login 리다이렉트
                  - 회원가입 항상 400 실패
                  - 종목 수정 훅 없음
                  - 토큰 자동 갱신 없음
                  
2026-06-09 저녁: 전체 수정 + 사후 분석 문서 작성
```

**핵심 교훈**: FE 구현 29개 카드를 하루에 완성하는 속도는 인상적이었으나, 각 카드에서 BE 실제 구현을 확인하는 비용을 절약했고 그 비용이 사후 수정으로 돌아왔다. **빠름과 정확함의 교환 비용**을 명확히 인식해야 한다.

---

## 6. 관련 문서

- [[frontend-full-ui-implementation]] — Done (W1~W7 전체 구현)
- [[frontend-api-integration]] — Draft (실 API 연동 후속 Spec)
- [[api_spec]] — REST API 명세 (§2.1~§2.6 갱신 필요)
- [[db_schema]] — 포트폴리오·알림 응답 스키마 보완 필요
