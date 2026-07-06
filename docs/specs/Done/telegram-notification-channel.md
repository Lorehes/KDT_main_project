---
type: spec
status: Done
created: 2026-07-05
updated: 2026-07-06
---

# 텔레그램 알림 채널 실발송 Spec (telegram-notification-channel)

> 상태: Approved → **Done** (2026-07-06, Wave 1~3 구현 완료 — 커밋 4ff1ae1)

## 배경 / 목적

기획([[DART공시통역_통합기획서]] §9.1)상 알림 1순위 채널은 **카카오 알림톡**이나,
비즈메시지 채널 심사 통과 전까지 운영 불가([[kakao-notification-channel]] Done, Wave 3 보류).
현재 실발송 가능 채널은 EMAIL뿐이고, **TELEGRAM은 스텁 상태**다:

- BE: `ChannelSender`가 `TELEGRAM → markUnsupported("TELEGRAM not supported in MVP")` → 무조건 FAILED
- FE: 알림 설정에서 선택은 가능하나 `comingSoon: true` "곧 지원 예정" 배지
- DB: `users`에 텔레그램 `chat_id` 컬럼 없음 (연락처는 이메일 + 암호화 전화번호뿐)

이 Spec은 **텔레그램 봇 실발송 + 사용자 계정 연동**을 완성해 즉시 푸시 채널을 확보한다.
카카오 알림톡 1순위 기획은 유지 — 심사 통과 시 활성화하면 되고, 텔레그램은 §9.1의 공식 폴백 채널이다.

- **페르소나**: A(카톡 3줄 요약 대체 경험) · B(즉시 알림이 핵심 가치) · E(큰 글자 — 텔레그램 자체 설정 활용)
- **BM 티어**: **전 티어 무료 확정 — MVP 이후에도 유지** (사용자 결정 2026-07-06). 기획서 §8.1의 Premium "텔레그램 봇/웹훅 API" 항목은 알림 수신 채널이 아니라 웹훅 API 제공을 의미하는 것으로 해석하되, 표현 정리는 /dc-doc-sync 후속

### 현황 (코드 실측)

| 항목 | 상태 |
|------|------|
| `AnalysisCompletedEvent` → `NotificationDispatcher` 4단계 필터 → 발송 | ✅ 완료 ([[notification-dispatcher]]) |
| `ChannelSender` 채널 라우팅 + `NotificationMessageBuilder`(면책·판단보류 포함) | ✅ 완료 |
| `NotificationRetryJob` 5분 배치 재발송 (MAX_RETRY=3) | ✅ 완료 ([[notification-retry-job]]) |
| `notifications` 테이블 + `uq_notification_dedup` 멱등 | ✅ V6/V15/V18 |
| KAKAO(`KakaoAlimtalkClient`)·EMAIL(`MailNotificationClient`) 클라이언트 | ✅ 완료 |
| **TELEGRAM 발송 클라이언트 / chat_id / 설정 프로퍼티** | ❌ **전무 — 이번 범위** |

## 요구사항

- [ ] R1. `users.telegram_chat_id` 컬럼 추가 — Flyway **V30** (최신 V29 확인, V19 결번 주의)
- [ ] R2. `infrastructure/telegram/TelegramClient` — Bot API `sendMessage` 호출. 기존 패턴 복제: `RestClient` + JDK HttpClient 타임아웃 + `@Retryable`(지수 백오프) + `HostWhitelist` + `SecretMasker`
- [ ] R3. `TelegramProperties` — `@ConfigurationProperties("dartcommons.telegram")`: `bot-token`(환경변수 `TELEGRAM_BOT_TOKEN`), `bot-username`, `timeout-ms`, `max-retries`. **토큰 하드코딩 금지** ([[CLAUDE]] §7)
- [ ] R4. `ChannelSender`의 `case TELEGRAM` — `markUnsupported` 제거 → 실발송. chat_id 미연동 사용자는 `FAILED("TELEGRAM_NOT_LINKED")` (재시도 무의미 → retry 대상 제외 여부 tech-review)
- [ ] R5. 계정 연동 흐름 — `POST /api/v1/notifications/telegram/link` → 일회용 토큰 발급 + 딥링크(`https://t.me/{bot}?start={token}`) 반환. 봇 `getUpdates` 폴링 잡이 `/start {token}` 수신 → token↔user 매칭 → chat_id 저장
- [ ] R6. 알림 문구 — 아래 "메시지 템플릿" 확정안 적용. 기존 `NotificationMessageBuilder` 본문 재사용 + 텔레그램용 조립(HTML parse_mode + 상세 링크)
- [ ] R7. 설정 API 응답에 `telegram_linked` 불리언 추가 (`GET /notifications/settings`) — chat_id 원값은 응답 미노출
- [ ] R8. FE — 알림 설정 페이지 `comingSoon` 배지 제거, "텔레그램 연동" 버튼(딥링크 새 창) + 연동 상태 표시 + 미연동 상태로 TELEGRAM 저장 시 안내
- [ ] R9. 테스트 — `TelegramClient` 단위(mock 서버), 디스패처→텔레그램 경로 Testcontainers 통합 테스트 (Mock DB 금지, [[CLAUDE]] §6-6)
- [ ] R10. `.env.example`·운영가이드에 `TELEGRAM_BOT_TOKEN` 항목 추가

## 메시지 템플릿 (R6 확정안)

근거: 통합기획서 §9.3(텔레그램=Markdown 포맷) · §11.2(면책 문구) · [[kakao-notification-channel]] R6 알림톡 템플릿과 톤 통일 · §6-5(색상 단독 금지 → 이모지+텍스트 병용).

```
📢 <b>[DART 공시 알림]</b> {corp_name} ({stock_code})

{sentiment_badge} <b>{report_nm}</b>

{summary — 3줄 요약}

신뢰도 {confidence_pct}%

🔗 상세 분석 보기: {FRONT_BASE_URL}/disclosures/{disclosure_id}

※ 본 분석은 정보 제공 목적이며 투자 자문·권유가 아닙니다.
AI 분석은 부정확할 수 있으며 투자 판단의 책임은 이용자에게 있습니다.
```

- `sentiment_badge`: `🔵 악재` / `🔴 호재` / `⚪ 중립` — 색상(이모지) + 텍스트 병용
- **판단 보류** (`is_withheld=true`): sentiment_badge 대신 `⏸ 판단 보류 — 신뢰도가 낮아 호재/악재 판단을 보류합니다` (신뢰도 없이 단정 금지, [[CLAUDE]] §7)
- parse_mode는 `HTML` 권장 (MarkdownV2는 이스케이프 지옥 — `_`, `*`, `[` 등 18자). 본문 사용자 데이터(`corp_name`·`report_nm`·`summary`)는 HTML 엔티티 이스케이프 필수
- 텔레그램 메시지 한도 4,096자 — `summary` 길이 캡(초과 시 말줄임)
- 면책 문구·판단 보류 로직은 기존 `NotificationMessageBuilder`에 이미 존재 — **문구 SSOT를 빌더에 유지**하고 텔레그램 조립부는 포맷만 담당 (구현 시 기존 문구와 위 확정안 대조 후 통일)

## 영향 범위 (조사 결과)

- 영향 레이어: backend(notification, infrastructure, user) / frontend(notifications/settings)
- 영향 파일:
  - `backend/src/main/resources/db/migration/V30__add_telegram_chat_id_to_users.sql` — 신규
  - `backend/src/main/java/com/dartcommons/infrastructure/telegram/TelegramClient.java` · `TelegramProperties.java` — 신규
  - `backend/src/main/java/com/dartcommons/notification/services/ChannelSender.java` — `case TELEGRAM` 교체
  - `backend/src/main/java/com/dartcommons/notification/services/NotificationMessageBuilder.java` — 텔레그램 포맷 메서드 추가(본문 SSOT 재사용)
  - `backend/src/main/java/com/dartcommons/user/entities/UserEntity.java` — `telegramChatId` 필드
  - `backend/src/main/java/com/dartcommons/user/controllers/NotificationSettingsController.java` · `NotificationSettingsService.java` — link 엔드포인트 + `telegram_linked`
  - `backend/src/main/java/com/dartcommons/user/services/NotificationHistoryService.java:132` — TELEGRAM 예외 throw 제거
  - `backend/src/main/resources/application.yml` — `dartcommons.telegram.*`
  - `frontend/src/app/(app)/notifications/settings/page.tsx` — comingSoon 제거 + 연동 UI
  - `frontend/src/lib/api/notifications.ts` — link API 추가
- DB 변경: **Flyway V30 필요** (`telegram_chat_id`). 연동 일회용 토큰은 DB 미저장 — Caffeine 캐시(TTL 10분)로 충분
- 외부 계약: **Telegram Bot API 신규** (`api.telegram.org` — HostWhitelist 등재 필요). DART/KRX/카카오/LLM 계약 변경 없음

## 관련 패턴 / 과거 사례 (Step 0 결과)

- `docs/solutions/` 없음. 대신 Done spec이 SSOT:
  - [[notification-dispatcher]] — 디스패처·4단계 필터·멱등·면책 문구 원칙
  - [[kakao-notification-channel]] — 채널 클라이언트 패턴 + 알림톡 템플릿 문구 + "텔레그램은 MVP 이후" 결정 기록 → **본 Spec이 그 후속**
  - [[notification-retry-job]] — 재시도 상태머신 (텔레그램도 자동 편입됨)
- 클라이언트 구현 참조: `infrastructure/kakao/KakaoAlimtalkClient.java` (RestClient + `@Retryable` + Properties + dev placeholder skip 분기 — 텔레그램도 동일하게 dev 모드 skip 권장)
- dev-log: ChannelSender `markUnsupported` 처리 이력, FE `comingSoon` 플래그 추가 이력 확인

## 리스크 / 법적 검토

- **자본시장법 (§11.1)**: 매수/매도 권유 표현 금지 — 템플릿에 없음. 면책 문구 모든 메시지 하단 필수(템플릿 포함). 신뢰도 낮으면 판단 보류 표기
- **개인정보**: `telegram_chat_id`는 개인 식별자 — 금융정보(AES-256 대상)는 아니나 응답 평문 미노출(`telegram_linked` 불리언만) + 로그 마스킹. 평문 저장 vs 암호화는 tech-review 결정(권장: 평문 VARCHAR + 미노출 — 전화번호와 달리 단독 악용도 낮고 봇 차단으로 무력화 가능)
- **오연동 방지**: chat_id 수동 입력 방식은 배제 — 딥링크 토큰 방식으로 본인 소유 채팅만 연동됨 (텔레그램 특성상 사용자가 봇을 먼저 시작해야 발송 가능하므로 타인 chat 오발송 원천 차단)
- **봇 차단(403 "bot was blocked")**: 발송 실패 시 재시도 무의미 — chat_id 해제(unlink) + FAILED 종결 처리
- **레이트리밋**: Bot API 초당 ~30건. MVP 사용자 규모에선 비문제, 429 수신 시 기존 백오프 재시도로 흡수 — 대량화 시 발송 큐 스로틀은 후속

## 권장 구현 방향

**연동 방식 — A안 채택 권장: 딥링크 + `getUpdates` 폴링**

| | A. 딥링크 + getUpdates 폴링 (권장) | B. Webhook 수신 |
|---|---|---|
| 구조 | `@Scheduled` 폴링 잡 (기존 RetryJob 패턴 그대로) | 공개 HTTPS 인바운드 엔드포인트 신설 |
| 보안 면적 | 아웃바운드만 — 추가 노출 없음 | 인바운드 검증(secret token)·보안 리뷰 부담 |
| MVP 적합성 | 단일 인스턴스·저트래픽에 충분 | 실시간성 이점이 연동 1회성 흐름엔 과함 |

연동은 가입 후 1회성 이벤트라 폴링 지연(수 초)이 UX에 무해. 폴링 잡은 연동 대기 토큰이 있을 때만 동작(Caffeine 캐시 비면 skip)하면 상시 호출 낭비도 없음.

**wave 분해 제안** (tech-review에서 확정):
1. Wave 1 (BE 코어): V30 + TelegramClient/Properties + ChannelSender 교체 + 메시지 포맷 — 이 시점부터 chat_id 수동 세팅으로 발송 검증 가능
2. Wave 2 (연동 흐름): link 엔드포인트 + 폴링 잡 + `telegram_linked`
3. Wave 3 (FE + 마감): 설정 페이지 연동 UI + comingSoon 제거 + `.env.example`/운영가이드

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-07-06)

> 검토 기준: 코드 실측(ChannelSender·NotificationDispatcher·NotificationMessageBuilder·KakaoAlimtalkClient·HostWhitelist·NotificationSettingsService/Response·NotificationRetryJob·application.yml·.env.example). Spec의 요구사항/영향 파일은 대부분 정확 — 아래는 실측으로 **확정·수정된 사항**과 작업 분해.

### 아키텍처 분해

- 영향 레이어: backend(infrastructure/telegram · notification · user) / frontend(notifications/settings)
- **신규**: `TelegramClient` · `TelegramProperties` · `TelegramUpdatePollingJob` · `TelegramLinkService`(토큰 캐시) · `TelegramLinkController`(또는 기존 설정 컨트롤러 확장) · Flyway `V30`
- **수정**: `ChannelSender`(case TELEGRAM) · `NotificationMessageBuilder`(채널 인지 본문) · `NotificationDispatcher`(본문 조립 분기) · `UserEntity`(telegramChatId + 갱신 메서드) · `NotificationSettingsResponse`(telegram_linked) · `HostWhitelist`(PROD_ALLOWED) · `application.yml` · `.env.example` · FE 설정 페이지·API 레이어

### 실측으로 확정/수정된 설계 결정

1. **본문은 발송 시점에 조립되어 `notifications.message_body`에 저장**된다 (`NotificationDispatcher.dispatchForUser` → `record.storeMessage(body, subject)`, RetryJob이 재사용). → **텔레그램 HTML 본문은 ChannelSender가 아니라 조립 시점(빌더)에서 생성**해야 한다. `ChannelSender`는 저장된 body를 그대로 전송만 함(카카오/이메일과 동일). 따라서 `NotificationMessageBuilder`에 **채널 인지 오버로드**(`buildBody(disclosure, sentiment, confidence, channel)`)를 추가하고 `Dispatcher`가 `user.getNotifyChannel()`로 분기하는 것이 정합. HTML 이스케이프·상세 링크(`disclosure_id` 사용 가능)는 빌더 책임. → **Spec R6의 "ChannelSender에서 포맷" 표현을 "빌더에서 채널 인지 조립"으로 정정.**
2. **재시도 의미론은 기존 패턴에 그대로 편입.** 영구 실패(chat_id 미연동·봇 차단 403)는 `ChannelSender`가 `markFailed()` → 종결(RetryJob 재조회 대상 아님, `status IN (PENDING,RETRYING)`만 재시도). 일시 실패(타임아웃·5xx·429)는 `TelegramClient`의 `@Retryable`이 흡수하고, 소진 시 throw → Dispatcher가 PENDING 유지 → RetryJob 픽업. → **Spec R4의 "retry 대상 제외 여부"는 이미 해결됨: markFailed면 자동 제외.**
3. **폴링 잡은 반드시 `@ConditionalOnProperty("dartcommons.scheduling.enabled", matchIfMissing=true)` 부착** — RetryJob과 동일. 미부착 시 테스트에서 실제 텔레그램 `getUpdates` 호출 발생(테스트는 scheduling.enabled=false로 전역 차단됨).
4. **HostWhitelist**: `api.telegram.org`를 `PROD_ALLOWED`에 추가 필수. 미추가 시 `TelegramClient` 생성자 `verify()`에서 부팅 실패(빠른 실패).
5. **설정 API 경로 확정**: `NotificationSettingsController`는 `/api/v1/notifications/settings`(GET/PUT). link 엔드포인트는 `POST /api/v1/notifications/telegram/link`로 신규(별도 컨트롤러 또는 설정 컨트롤러 확장 — 구현자 재량). `NotificationSettingsResponse`(record)에 `@JsonProperty("telegram_linked") boolean` 추가 + `from(user)`에서 `user.getTelegramChatId() != null` 파생.
6. **yml/env 패턴 확정**: `dartcommons.telegram.{bot-token,bot-username,timeout-ms,max-retries}`, 값은 `${TELEGRAM_*:placeholder}`. 카카오처럼 **placeholder 시 dev 모드 skip**(실 API 미호출 + `[DEV]` 로그) 분기를 `TelegramClient`에 동일 적용 — 로컬/테스트 부팅 안정.

### 미해결 → 본 검토에서 확정

- **티어 게이트**: **전 티어 무료 확정 — MVP 이후에도 유료화 없음** (사용자 결정 2026-07-06). tier 가드 코드 불필요. 기획서 §8.1 Premium "텔레그램 봇/웹훅 API"와의 표현 정합은 /dc-doc-sync 후속.
- **환경변수**: `TELEGRAM_BOT_TOKEN` 등은 **구현 전 사용자가 직접 주입 예정** (사용자 확정 2026-07-06). placeholder dev 모드 분기는 그대로 구현하되, Wave 1 실발송 검증은 실토큰 주입 후 진행.
- **chat_id 저장 형식**: **평문 `VARCHAR(32)` 확정**(암호화 불필요). 근거: 금융 PII(매수가/전화번호) 아님, 텔레그램 chat_id 단독으로는 봇 차단 시 무력화, 응답 미노출(`telegram_linked` 불리언만)로 노출면 차단. `_enc`/BYTEA 패턴 미적용.

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | Flyway `V30__add_telegram_chat_id_to_users.sql` (`telegram_chat_id VARCHAR(32)`, nullable) | backend/db | BE | 하 | - |
| 2 | `UserEntity.telegramChatId` 필드 + `linkTelegram(chatId)`/`unlinkTelegram()` 메서드 | backend/user | BE | 하 | #1 |
| 3 | `HostWhitelist`에 `api.telegram.org` 추가 | backend/shared | BE | 하 | - |
| 4 | `TelegramProperties`(`@ConfigurationProperties("dartcommons.telegram")`, `@Validated`) + `application.yml` 블록 + `.env.example` | backend/infra | BE | 하 | - |
| 5 | `TelegramClient.send(chatId, htmlBody)` — RestClient+JDK타임아웃+`@Retryable`+HostWhitelist+dev placeholder skip (KakaoAlimtalkClient 복제). Bot API `sendMessage`, `parse_mode=HTML`, `disable_web_page_preview` | backend/infra | BE | 중 | #3,#4 |
| 6 | `NotificationMessageBuilder` 채널 인지 오버로드 — 텔레그램 HTML 본문(이모지 배지·판단보류·신뢰도·상세 링크·면책, HTML 이스케이프, 4096자 캡) | backend/notification | BE | 중 | - |
| 7 | `NotificationDispatcher` 본문 조립을 채널 분기(`getNotifyChannel()`)로 | backend/notification | BE | 하 | #6 |
| 8 | `ChannelSender` `case TELEGRAM` 실발송 — chat_id null→`markFailed("TELEGRAM_NOT_LINKED")`, 봇차단(403)→markFailed, 그 외 send | backend/notification | BE | 중 | #2,#5 |
| 9 | `TelegramLinkService` — 일회용 토큰 발급(Caffeine TTL 10분) + `POST /notifications/telegram/link`(딥링크 반환) | backend/user | BE | 중 | #4 |
| 10 | `TelegramUpdatePollingJob` — `@Scheduled`+`@ConditionalOnProperty` getUpdates(offset 인메모리) → `/start {token}` 파싱 → 토큰↔user 매칭 → `linkTelegram()` | backend/user·infra | BE | 상 | #2,#5,#9 |
| 11 | `NotificationSettingsResponse.telegram_linked` 추가 + `from()` 파생 | backend/user | BE | 하 | #2 |
| 12 | FE 설정 페이지 — `comingSoon` 제거 + "텔레그램 연동" 버튼(딥링크 새 창)·연동 상태·미연동 저장 안내 | frontend | FE | 중 | #9,#11 |
| 13 | FE `lib/api/notifications.ts` link API + 연동 상태 훅 | frontend | FE | 하 | #9 |
| 14 | 테스트 — `TelegramClient` 단위(MockWebServer) + 디스패처→텔레그램 Testcontainers IT(연동/미연동/봇차단) | backend/test | BE | 중 | #8,#10 |
| 15 | `NotificationHistoryService`의 TELEGRAM 예외 throw 제거(스텁 정리) | backend/user | BE | 하 | #8 |

### DB / 마이그레이션 영향

- **신규 파일**: `V30__add_telegram_chat_id_to_users.sql` — `ALTER TABLE users ADD COLUMN telegram_chat_id VARCHAR(32);`(nullable, 기존 사용자 영향 없음). 최신 V29 확인, V19 결번(무관).
- 인덱스 불필요(발송 시 user 단건 조회, chat_id 역조회 없음). 연동 토큰은 DB 미저장(Caffeine).

### 외부 계약 영향

- **신규**: Telegram Bot API (`https://api.telegram.org/bot<token>/sendMessage`·`/getUpdates`). HostWhitelist 등재.
- DART/KRX/카카오/LLM/Chroma 계약 변경 **없음**. 기존 카카오·이메일 발송 경로 무영향(switch case 추가만).

### 리스크 & 법적 검토

- **자본시장법 §11.1**: 템플릿에 권유 표현 없음. 면책 문구·판단보류는 기존 빌더 로직 계승 → HTML 본문에도 필수 포함(카드 #6 수용 기준).
- **개인정보**: chat_id 평문 저장이나 금융 PII 아님·응답 미노출·로그 마스킹(`chatId=[REDACTED]`). 봇 차단 시 즉시 무력화.
- **오연동**: 딥링크 토큰 방식 → 본인이 봇을 `/start`한 채팅만 연동, 타인 chat 오발송 원천 차단(수동 chat_id 입력 배제).
- **폴링 잡 인메모리 상태**(offset·토큰 캐시): 재시작 시 유실·다중 인스턴스 미지원. **MVP 단일 인스턴스 전제 OK** — 다중화 시 offset/토큰 DB or Redis 이관은 후속(§16 Phase 3 큐 승격과 함께).
- **getUpdates ↔ webhook 상호 배타**: 폴링 채택 시 봇에 webhook 미설정 상태 유지 필요(운영가이드 명시).

### 예상 wave 수

3개 wave (Spec 제안과 동일, 카드 매핑 확정):
- **Wave 1 — BE 발송 코어**: #1~#8, #15 (chat_id 수동 세팅으로 실발송 검증 가능한 지점)
- **Wave 2 — 연동 흐름**: #9, #10, #11 + IT #14 일부
- **Wave 3 — FE + 마감**: #12, #13, 운영가이드/`.env.example` 확정, #14 잔여

> 판정: **구현 가능(Approved 승격 권장)**. 미해결 2건(티어 게이트·chat_id 저장)은 본 검토에서 확정됨. 잔여 열린 항목은 "폴링 인메모리 상태의 다중 인스턴스 이관"뿐이며 MVP 범위 밖 후속 이슈로 분리.

## 구현 리뷰 결과 (dc-implement + dc-review-code · 2026-07-06)

구현 완료(Wave 1~3 + 테스트). 5 페르소나 리뷰(risk=high) 후 수정 반영:

- **P0 2건 수정**: ① link 발급 폭주 시 타인 대기 토큰 LRU 방출 DoS — 토큰 저장을 userId 키 1인 1슬롯(Caffeine) + token 역인덱스(ConcurrentHashMap)로 재구조화, ② 토큰 소비 비원자성(getIfPresent+invalidate) — `remove()` 원자 연산으로 교체(동시 /start 이중 소비 차단)
- **P1/P2 8건 수정**: frontBaseUrl·stockCode HTML 이스케이프, 테스트발송 403→chat_id 해제+422, 폴링 토큰 32자 사전 검증, `telegramChatId` `@JsonIgnore`, link 응답 `Cache-Control: no-store`, FE deep_link `t.me` 접두사 검증+팝업 차단 toast 폴백, ChannelSender의 user write를 `TelegramBotBlockedEvent` 이벤트 경유로 교체(§3-2 준수), IT summary 경로 픽스처 보강
- **보류 → 후속 이슈**: 디스패처 직렬 발송·유저 N+1(카카오 채널과 동일한 기존 구조 — 발송 큐 스로틀과 함께 후속), DEBUG 로깅 시 봇 토큰 URL 노출(운영가이드에 `logging.level.org.springframework.web.client=WARN` 강제 명시), Properties `maxRetries` dead prop(DART/KRX/카카오 포함 일괄 정리 대상)
- 검증: 텔레그램 IT 6/6 + 빌더 단위 6/6 + notification·user 패키지 회귀 통과, FE tsc 통과
