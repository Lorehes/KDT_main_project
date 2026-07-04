---
type: doc
status: done
created: 2026-06-09
updated: 2026-06-09
---

# FE-BE 정합성 불일치 근본원인 분석

> 이 문서는 `frontend-full-ui-implementation` Spec Done 직후 발견된 정합성 불일치 12건(P0 2건·P1 5건·P2 5건)의 **왜(Why)**를 코드·기획·프로세스 세 관점에서 분석하고 재발 방지 체계를 정리한다.
> 수정 내용 자체는 [[postmortem-fe-be-misalignment]]에 기술돼 있으며, 본 문서는 구조적 원인에 집중한다.

---

## 1. 발견된 문제 목록 (최종 확정)

| # | 심각도 | 불일치 내용 | 영향 |
|---|---|---|---|
| P0-1 | **P0** | 알림 설정 경로: FE `/notifications/settings` ≠ BE `/users/me/notifications` | 알림 설정 전체 404 |
| P0-2 | **P0** | `PortfolioRequest` snake_case 역직렬화 실패 | 포트폴리오 등록/수정 항상 400 |
| P1-1 | P1 | `GET /notifications` 알림 이력 컨트롤러 없음 | 알림 센터 전체 404 |
| P1-2 | P1 | `POST /notifications/test` 컨트롤러 없음 | 알림 테스트 404 |
| P1-3 | P1 | `GET /disclosures`, `/disclosures/{id}`, `/disclosures/{id}/analysis`, `POST /analyses/{id}/feedback` 컨트롤러 없음 | 서비스 핵심 기능 전체 404 |
| P1-4 | P1 | `RefreshRequest.refreshToken` `@JsonProperty` 누락 | 토큰 갱신/로그아웃 400 실패 |
| P1-5 | P1 | `AnalysisResponse` camelCase 직렬화 | FE 분석 응답 모든 필드 undefined |
| P2-1 | P2 | `UserMeResponse`에 `tier_expires_at` 누락 | FE 구독 만료 표시 항상 undefined |
| P2-2 | P2 | `UpdateMeBody.investment_experience·preferred_time` BE 미지원 | 프로필 저장 시 400 또는 무시 |
| P2-3 | P2 | `UpdateMeBody.nickname` optional ↔ BE `@NotBlank` | 닉네임 없이 PATCH 시 400 |
| P2-4 | P2 | `Portfolio.notify_enabled` 허구 필드 | 항상 undefined, 렌더 의도 불명 |
| P2-5 | P2 | `signup/profile` PATCH가 BE 미지원 필드 전송 | 선택 단계 에러(무시·스킵) |

---

## 2. 근본원인 분석

### 2-1. 코드 관점: "스펙 신뢰 vs 구현 신뢰" 구분 부재

#### 패턴 A — 명세(api_spec.md) 기준으로 FE 구현, BE는 다르게 구현

```
발생 케이스: P0-1(알림 경로), P0-2(PortfolioRequest 필드), P1-4(RefreshRequest)
```

`api_spec.md`는 설계 합의 문서이지 구현 현황 문서가 아니다. BE 개발자가 편의나 단순화를 이유로 명세와 다르게 구현했을 때, **명세 업데이트 게이트가 없으면** FE는 outdated 명세를 신뢰하게 된다.

- `NotificationSettingsController`는 `/users/me/notifications`로 구현됐으나 명세는 `/notifications/settings`. 어느 시점에 경로가 바뀌었는지 추적 불가.
- `PortfolioRequest`에 `@JsonProperty` 없이 구현 → Jackson 기본 camelCase 역직렬화 → FE의 snake_case JSON 매핑 실패.
- `RefreshRequest`의 `refreshToken` 필드에 `@JsonProperty("refresh_token")` 없음 → FE Route Handler가 보내는 snake_case 역직렬화 실패.

**핵심 원인**: BE 구현 변경 시 api_spec.md 동기 업데이트 의무가 없었다.

#### 패턴 B — FE가 BE 미구현 엔드포인트를 가정하고 구현

```
발생 케이스: P1-1(알림 이력), P1-2(알림 테스트), P1-3(공시·분석 전체)
```

이것이 가장 큰 근본 문제다. `frontend-full-ui-implementation`의 29개 카드가 구현될 때, **대응하는 BE 컨트롤러가 존재하는지 확인하지 않았다**.

공시·분석 도메인은 수집 파이프라인(`DisclosurePollingJob`, `AnalysisOrchestrator`)은 완성돼 있었지만, 사용자에게 데이터를 제공하는 REST 레이어(`DisclosureController`, `AnalysisController`)가 없었다. Wave 분리가 FE 내부 의존성 기준으로만 이뤄져, "이 컴포넌트는 어떤 BE 엔드포인트를 소비하는가?" 검증 시점이 없었다.

**핵심 원인**: FE 구현 카드에 "BE 엔드포인트 존재 여부 확인" 게이트가 없었다.

#### 패턴 C — FE가 기능 요구사항을 추론해 없는 필드 생성

```
발생 케이스: P2-1(tier_expires_at 누락), P2-2(investment_experience 허구), P2-4(notify_enabled 허구)
```

`PortfolioResponse`에 `notify_enabled`가 없는데 FE에서 "종목별 알림 on/off가 있을 것"이라는 가정으로 인터페이스를 선설계했다. `investment_experience`·`preferred_time`도 기획서에 있는 기능 요건이나 BE `UpdateMeRequest`에는 구현되지 않은 상태였다.

반대로 `tier_expires_at`은 `UserEntity`에는 있으나 `UserMeResponse`에 빠트린 BE 측 누락이다.

**핵심 원인**: BE 실제 DTO를 직접 읽지 않고 기획 요건 + 추론으로 FE 타입을 작성했다.

#### 패턴 D — 직렬화/역직렬화 전략 불명확

```
발생 케이스: P0-2, P1-4, P1-5(AnalysisResponse camelCase)
```

프로젝트 전체에 Jackson 직렬화 전략(`spring.jackson.property-naming-strategy`)이 명시적으로 설정돼 있지 않다. 각 DTO가 `@JsonProperty`를 선택적으로 쓰는데, 일부 DTO는 누락됐다.

- 응답 DTO(`AuthResponse`, `UserMeResponse`, `PortfolioResponse`): `@JsonProperty` 적용됨 ✓
- 요청 DTO(`PortfolioRequest`, `RefreshRequest`): `@JsonProperty` 누락 ✗
- 분석 응답 DTO(`AnalysisResponse`): `@JsonProperty` 누락 → camelCase 직렬화 ✗

**핵심 원인**: "요청 DTO는 snake_case로 받는다"는 규칙이 없고, DTO별로 선택적 적용 → 누락 발생.

---

### 2-2. 기획 관점: 상세도 부족이 구현 갈림 유발

#### API 명세의 응답 예시 부재

`api_spec.md §2.3`(공시 상세)·`§2.5`(알림 이력)에 응답 JSON 예시가 없다. FE 개발자는 `Disclosure`, `Notification` 인터페이스를 **추론**으로 작성했다. 응답 예시가 있었다면 `corp_name`이 포함인지 아닌지, `sentiment`가 어디서 오는지 명확했을 것이다.

```
발생 케이스: P2-4(notify_enabled), P1-3(공시 응답 구조 추론)
```

#### BE 개발 우선순위가 파이프라인에 치우침

BE 개발은 수집(`DisclosurePollingJob`) → 분석(`AnalysisOrchestrator`) → 알림(`NotificationDispatcher`) 파이프라인을 완성하는 데 집중했다. 사용자 대면 REST 레이어는 M2(user 도메인 합류) 시점에 추가 예정이었으나, FE는 이미 해당 엔드포인트가 있다고 가정하고 구현했다.

```
기획서의 로드맵:
  M1: 파이프라인 (수집→분석→알림)
  M2: 사용자 (인증·포트폴리오·알림설정)
  → 공시/분석 REST API는 M2 이후

FE 구현 전제:
  공시 피드, 분석 상세, 피드백 → 이미 BE에 있다고 가정
```

**핵심 원인**: FE 구현 타이밍과 BE 개발 로드맵이 명시적으로 연결되지 않았다.

#### 인증 토큰 전달 방식 미확정

`api_spec.md`에 "httpOnly Cookie 방식 권장"이라고만 기술됐고 언제 전환할지 결정되지 않았다. BE는 JSON body, FE는 httpOnly Cookie를 각자 구현했다(이전 postmortem P0-1로 Route Handler 브리지로 해결됨). 동일한 미확정 패턴이 `RefreshRequest` 필드명에도 반복됐다.

---

### 2-3. 프로세스 관점: 검증 게이트 공백

#### 현재 프로세스의 구조적 공백

```
dc-plan → dc-tech-review → dc-implement → dc-review-code → dc-push
                                ↑
                   "BE 실제 컨트롤러 존재 확인" 없음
                   "응답 DTO 직접 읽기" 없음
```

`dc-implement`는 `api_spec.md`를 읽고 구현하도록 설계됐으나, api_spec.md와 실제 BE 구현이 동기화되지 않은 상황에서는 잘못된 명세를 신뢰하게 된다.

**필요한 게이트**: FE dc-implement 시작 전, "관련 BE Controller/DTO 파일을 Read로 직접 확인했는가?"

#### Wave 분리의 사각지대

W1~W7이 FE 내부 의존성(`@/components/*`, `@/lib/api/*`)으로만 분리됐다. BE 계약 검증 시점이 전혀 없었다. 이상적 구조:

```
Wave N 시작 전:
  1. 이 Wave에서 호출할 BE 엔드포인트 목록 파악
  2. 각 엔드포인트의 Controller 파일 직접 Read
  3. 응답 DTO 필드명·타입 확인
  → 불일치 발견 시 즉시 修정 또는 플래그
```

#### BE 변경 시 api_spec.md 동기화 미흡

```
현재: BE Controller 경로 변경 → api_spec.md 미반영 (드리프트)
필요: BE wave 커밋 시 "변경된 엔드포인트를 api_spec.md에 반영했는가?" 체크
```

`/dc-push` WORKLOG에 BE wave 완료 시 api_spec.md 대조 단계가 없었다.

---

## 3. 수정 내용 요약

### 수정 방향 결정 원칙

> BE vs FE 중 어느 쪽을 수정할지 결정할 때: **변경 범위가 작고 cascade 가 없는 쪽** + **Spec 의도와 일치하는 쪽**을 선택.

| 이슈 | 수정 방향 | 이유 |
|---|---|---|
| P0-1 알림 설정 경로 | **BE 경로 → `/notifications/settings`** | Spec 정합 + FE 변경 불필요 |
| P0-2 PortfolioRequest | **BE `@JsonProperty` 추가** | 전역 SNAKE_CASE 설정은 기존 DTO 사이드이펙트 위험 |
| P1-1~3 미구현 컨트롤러 | **BE 신규 구현** | 없는 것을 만드는 선택지 단일 |
| P1-4 RefreshRequest | **BE `@JsonProperty` 추가** | FE Route Handler가 snake_case 전송 패턴 정착 |
| P1-5 AnalysisResponse | **BE `@JsonProperty` 추가** | FE 타입 변경은 cascade 넓음 |
| P2-1 tier_expires_at | **BE DTO에 필드 추가** | UserEntity에 이미 있는 데이터, DTO 누락 수정 |
| P2-2~3 UpdateMeBody | **FE에서 미지원 필드 제거** | BE 유효 데이터 없음, FE 정리가 낙폭 최소 |
| P2-4 notify_enabled | **FE에서 제거, UI는 정적** | 허구 필드를 실 데이터 없이 사용 불가 |
| P2-5 profile/page | **FE API 호출 스킵** | 선택 단계이므로 완전히 skip해도 UX 무해 |

### BE 신규 생성 파일

| 파일 | 역할 |
|---|---|
| `disclosure/controllers/DisclosureController.java` | GET /disclosures, GET /disclosures/{id}, GET /disclosures/{id}/analysis |
| `disclosure/services/DisclosureQueryService.java` | 공시 피드 조회 서비스 (포트폴리오 필터 + bulk 분석 조인) |
| `disclosure/services/DisclosureListItemResponse.java` | 공시 목록/상세 응답 DTO |
| `analysis/controllers/AnalysisController.java` | POST /analyses/{id}/feedback |
| `analysis/services/AnalysisQueryService.java` | 분석 결과 조회 + 티어 차등 |
| `analysis/services/FeedbackService.java` | 피드백 upsert (신규/재투표) |
| `analysis/entities/FeedbackEntity.java` | feedbacks 테이블(V9) 엔티티 |
| `analysis/repositories/FeedbackRepository.java` | 피드백 리포지토리 |
| `analysis/dto/FeedbackRequest.java` | 피드백 요청 DTO |
| `user/controllers/NotificationController.java` | GET /notifications, POST /notifications/test |
| `user/services/NotificationHistoryService.java` | 알림 이력 조회 + 테스트 발송 |
| `user/dto/NotificationResponse.java` | 알림 이력 응답 DTO |
| `shared/dto/PageResponse.java` | 페이지네이션 공통 응답 래퍼 |

---

## 4. 재발 방지 체계

### 단기 (즉시 적용)

**규칙 1: FE dc-implement 시작 전 BE 계약 확인 의무**

```
FE Wave 시작 → "이 Wave가 소비하는 BE 엔드포인트 목록 작성"
→ 각 엔드포인트의 Controller.java + 응답 DTO.java 직접 Read
→ 엔드포인트 없음 → 즉시 BE 선행 구현 또는 플래그
→ 응답 DTO 필드 불일치 → 즉시 수정 후 진행
```

**규칙 2: BE DTO snake_case 정책 명확화**

```
요청 DTO (inbound): @JsonProperty snake_case 필수 (camelCase 필드에 snake_case 매핑)
응답 DTO (outbound): @JsonProperty snake_case 필수 (또는 전역 설정 채택)
```

전역 설정 채택 시: `application.yml`에 `spring.jackson.property-naming-strategy: SNAKE_CASE` 추가 + 모든 기존 DTO의 명시적 `@JsonProperty` 제거. 단, 이 작업은 전체 DTO 검토가 필요하므로 후속 Spec으로 분리.

**규칙 3: api_spec.md는 BE 구현이 변경될 때마다 즉시 동기화**

```
BE wave /dc-push WORKLOG에 다음 항목 추가:
  □ 변경된 엔드포인트 경로/메서드를 api_spec.md에 반영했는가?
  □ 변경된 요청/응답 DTO 필드를 api_spec.md에 반영했는가?
```

**규칙 4: 응답 DTO에 JSON 예시 필수**

api_spec.md에 엔드포인트를 기술할 때 응답 JSON 예시(`// 응답` 블록)를 반드시 포함한다. 예시 없는 타입 기술만으로는 FE가 추론에 의존하게 된다.

### 중기 (다음 Spec 단계)

**Jackson 전략 통일 Spec**

`spring.jackson.property-naming-strategy: SNAKE_CASE` 전역 적용 + 모든 기존 DTO 명시적 `@JsonProperty` 정리. 별도 Spec으로 분리해 한 번에 처리. 전역 전략 도입 후에는 개별 DTO에 `@JsonProperty` 불필요.

**OpenAPI 자동 생성 파이프라인**

springdoc-openapi가 이미 `/v3/api-docs`를 생성하고 있다. CI에서 이 JSON을 fetch → FE `openapi-generator`로 타입 자동 생성 → 수동 타입 작성 제거. 드리프트를 원천 차단하는 가장 확실한 장기 해법.

**FE-BE 계약 테스트**

`pact` 또는 간단한 Playwright + BE 통합 테스트로 FE가 기대하는 응답 형태를 BE가 실제로 반환하는지 자동 검증.

### 장기 (아키텍처)

**BE 개발 로드맵과 FE 구현 타이밍 명시적 연결**

FE Spec 작성(`dc-plan`) 시 "이 Spec이 의존하는 BE Spec" 목록을 명시한다. BE Spec이 Done 상태가 아니면 FE dc-implement는 시작할 수 없다(선행 조건). 현재는 이 연결이 없어 FE가 미완성 BE를 가정하고 구현한다.

---

## 5. 타임라인 재구성

```
2026-06-09 오전:  frontend-full-ui-implementation Spec → W1~W7 29개 카드 구현
                  각 Wave에서 BE 실제 파일 미확인, api_spec.md만 참조

2026-06-09 저녁:  정합성 감사 — P0 2건·P1 5건·P2 5건 발견
                  P0: 포트폴리오 등록 400, 알림 설정 404
                  P1: 공시/분석/알림 전체 404, 토큰 갱신 400, 분석 응답 undefined
                  P2: 허구 필드 5건

2026-06-09 저녁:  전체 수정 (BE 13개 신규 파일, 7개 수정 / FE 6개 수정)
                  근본원인 분석 문서 작성
```

**핵심 교훈**: "빠른 구현"과 "정확한 계약 검증" 사이의 트레이드오프. FE 29개 카드를 하루에 완성하는 속도 자체는 문제가 아니다. 각 카드에서 "이 API가 실제로 있는가?"를 확인하는 5분의 비용을 아꼈고, 그 비용이 사후 수정 3~4시간으로 돌아왔다.

---

## 6. 관련 문서

- [[postmortem-fe-be-misalignment]] — 이전 P0 4건 수정 사후 분석 (2026-06-09 오후)
- [[api_spec]] — REST API 명세 (§2.1~§2.6 갱신 반영됨)
- [[frontend-full-ui-implementation]] — Done (W1~W7 전체 구현)
- [[frontend-api-integration]] — Draft (실 API 연동 후속 Spec)
