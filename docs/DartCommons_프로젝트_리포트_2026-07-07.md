---
type: report
status: active
created: 2026-07-07
updated: 2026-07-07
---

# DartCommons — 프로젝트 종합 리포트

> **작성일**: 2026-07-07  
> **목적**: 발표자료 기반 — 프로젝트 시작~현재 전 과정, 기획 대비 실제 현황, 한계점, 로드맵

---

## 1. 프로젝트 개요

### 1.1 한 줄 정의

> **"보유 종목의 DART 공시를 실시간으로 받아 호재/악재 의미를 자연어로 해석해주는 개인 투자자용 AI 알림 서비스"**

### 1.2 해결하려는 문제

개인 투자자는 DART 공시를 수신해도 즉각 활용하지 못한다.

| 문제 | 원인 |
|------|------|
| 공시가 호재인지 악재인지 모름 | 전문 용어 + 복잡한 서식 |
| 공시를 시장 개장 중에 실시간 모니터링 불가능 | 직장인 등 물리적 제약 |
| 정보 비대칭 | 기관·외국인 대비 개인 투자자 불리 |
| 과거 유사 공시 학습 비용 | 자료 분산·검색 어려움 |

### 1.3 타겟 페르소나

| 페르소나 | 특징 | 핵심 니즈 |
|---------|------|---------|
| A. 장기 투자 직장인 (35세) | 5년차, 8개 종목 | 카카오톡 3줄 요약으로 즉시 판단 |
| B. 스윙 트레이딩 자영업자 (42세) | 8년차, 중소형주 | 공시 즉시 알림 + 빠른 매도 결정 |
| C. MZ 입문 투자자 (28세) | 1.5년차, ETF 위주 | 자연어 해석으로 공시 학습 |
| D. 투자 동아리 운영자 (33세) | 멤버에게 공시 정보 공유 | 공유 카드 바이럴 허브 |
| E. 퇴직 시니어 (58세, 2차) | 15년+, 배당주 | 큰 글자 카카오톡 핵심 3줄 |
| F. B2B 화이트라벨 (장기) | 증권사·핀테크 | 공시 분석 API 라이선스 |

### 1.4 시장 규모 추정

| 구분 | 정의 | 규모 |
|------|------|------|
| TAM | 주식 활동 계좌 전체 | ~7,000만 |
| SAM | 월 1회 이상 적극 거래자 | 800~1,200만 |
| SOM | 페르소나 A~D 해당층 | 200~300만 |

### 1.5 차별화 포인트

기존 서비스(DART 공식 앱, 증권사 앱, 뉴스 앱)가 **공시 원문 제목만 제공**하는 것과 달리:

1. **공시 원문 → 자연어 해석**: "전환사채 1,000억 발행 결정" → "주식 추가 발행 가능성, 기존 주주 불리 가능"
2. **과거 유사 공시 패턴**: "동일 유형 공시 시 평균 주가 -6.3% 반응"
3. **개인 포트폴리오 맥락**: "평균 매수가 5만원, 현재가 기준 손익선 위협 가능"
4. **하이브리드 RAG**: 룰 기반 정확성 + LLM + 벡터 검색으로 환각 최소화

### 1.6 글로벌 벤치마킹

| 사례 | 참고 포인트 | 본 서비스 적용 |
|------|----------|------------|
| Robinhood Gold | 무료 + 구독 구조, Gold 멤버 350만 | 공시 알림 무료 → 분석 상세 유료 |
| Seeking Alpha | 퀀트 레이팅 수치화, MAU 2,000만 | 영향도 점수(1~10) Pro 가치 증명 |
| Fiscal.ai | B2C 35만 → B2B API 기관 70개 | B2C 커뮤니티 확보 → B2B API 확장 |

---

## 2. 개발 타임라인

### 2.1 전체 개발 기간

**2026-05-27 ~ 2026-07-07 (약 6주)**

| 기간 | 단계 | 주요 성과 |
|------|------|---------|
| 05-27~05-30 | 기획 완성 & 부트스트랩 | 통합기획서, Spring Boot 3.5 스캐폴드, V1~V6 DB 마이그레이션 |
| 05-30~06-05 | M1: 수집 + Stage 2 LLM | 공시 91,989건 수집, qwen3:4b 분석 가동, 500건 smoke test |
| 06-07~06-09 | M2: 인증 + M3: 알림 | JWT+AES-256+OAuth2, 3채널 알림 디스패처 |
| 06-09~06-11 | W1~W7: 프론트 26화면 | Next.js 15 전체 UI 구현, 토큰 갱신 재설계 |
| 06-11~06-24 | 보안 리뷰 + 코드 정합 | 100+ 이슈 수정, FE-BE API 12건 불일치 해소 |
| 06-25~07-04 | M4: 배포 인프라 | Docker, nginx, GitHub Actions CI/CD |
| 07-05 | **M5: 프로덕션 배포** | gangwoncanvas.co.kr 라이브, HTTPS, 94k 공시 이관 |
| 07-06 | Stage 3 RAG 가동 | Chroma 93,560건 임베딩 → 서버 이관 |
| 07-07 | 현재 | Stage 4/5 구현, 알림채널 확정 대기 |

### 2.2 주요 이정표 상세

#### M0 — 부트스트랩 (2026-05-30)
- Java 21 + Spring Boot 3.5.14 + Gradle 8
- 6도메인 모듈 구조 확립 (`disclosure`, `analysis`, `notification`, `user`, `stocks`, `infrastructure`)
- V1~V6 Flyway 마이그레이션 (핵심 5테이블)

#### M1 — 공시 수집 + Stage 2 LLM (2026-06-05)
- DART OpenAPI 1분 폴링 + 90일 청크 백필 → **91,989건** 수집 완료
- Ollama qwen3:4b + PromptGuard (자본시장법 위반 9개 패턴 차단)
- 500건 smoke test: 평균 4.39초/건, confidence p50 0.700
- **⚠️ 데이터 손실 사고**: Docker named volume → bind mount 전환 (91k건 재수집)

#### M2 — 사용자 인증 (2026-06-07~08)
- Spring Security 6.4 + JWT (jjwt 0.12.6) + AES-256-GCM 암호화
- OAuth2 (카카오/구글/네이버) RestClient 완성
- Testcontainers 58개 통합 테스트 통과

#### M3 — 알림 디스패처 (2026-06-08)
- 3채널 (카카오/텔레그램/이메일) + 3빈도 (INSTANT/DAILY/WEEKLY)
- 지수 백오프 최대 3회 재시도, 채널 폴백 로직
- 79개 통합 테스트 통과

#### W1~W7 — 프론트엔드 26화면 (2026-06-09)
- Next.js 15 App Router + TypeScript + TailwindCSS 4 + shadcn/ui
- Route Groups: `(public)` / `(auth)` / `(app)`
- 토큰 갱신: Promise 큐 + BroadcastChannel + httpOnly 쿠키 (race condition 해소)

#### M4 — 배포 인프라 (2026-06-25)
- Dockerfile (BE/FE), docker-compose.prod.yml, nginx 리버스 프록시
- GitHub Actions CI/CD, Let's Encrypt HTTPS 자동화

#### M5 — 프로덕션 배포 (2026-07-05)
- **`https://gangwoncanvas.co.kr` 라이브**
- AWS Lightsail 단일 인스턴스, 서버 직접 빌드
- LLM: OpenRouter `nvidia/nemotron-3-super-120b-a12b:free`
- disclosures 94,355 + analysis_results 19,609 + stock_prices 28,258 이관

#### Stage 3 RAG 가동 (2026-07-06)
- Chroma + `nomic-embed-text` 임베딩 컨테이너 서버 추가
- 93,560건 임베딩: 로컬 Mac(건당 0.19초, 4시간 55분) → scp 서버 이관
- **서버 CPU 직접 임베딩 불가** (건당 11.7초, 추정 13일) → 교훈으로 기록

---

## 3. 기획 대비 실제 현황

### 3.1 기술 스택 정합성

| 계층 | 기획 | 실제 | 상태 |
|------|------|------|------|
| 백엔드 | Java 21 + Spring Boot 3.x | Java 21 + Spring Boot 3.5.14 | ✅ 정확히 일치 |
| ORM | Spring Data JPA + Hibernate | 동일 | ✅ |
| DB 마이그레이션 | Flyway | V1~V31 완료 | ✅ |
| 데이터베이스 | PostgreSQL | PostgreSQL 16 (Docker) | ✅ |
| 벡터 DB | Chroma | Chroma + nomic-embed-text | ✅ |
| LLM 통합 | LangChain4j 1.x | LangChain4j + OllamaLlmClient + OpenRouterLlmClient | ✅ |
| 인증 | Spring Security + JWT | Spring Security 6.4 + jjwt 0.12.6 | ✅ |
| OAuth2 | 카카오/구글/네이버 | 3대 소셜 RestClient 완성 | ✅ |
| 암호화 | AES-256 | AES-256-GCM (IV 12B + tag 16B) | ✅ 상향 구현 |
| 프론트엔드 | Next.js 15 + TS + TailwindCSS 4 | 동일 | ✅ |
| 상태 관리 | Zustand 5 + TanStack Query 5 | 동일 | ✅ |
| 폼 | React Hook Form + Zod | 동일 + @hookform/resolvers | ✅ |
| 차트 | Recharts 2 | 동일 | ✅ |
| 배포 | Docker + GitHub Actions | Dockerfile + compose.prod + nginx + GH Actions | ✅ |
| LLM 모델(MVP) | Ollama 로컬 | qwen3:4b (로컬) / nemotron-120b (프로덕션) | ✅ |
| 알림 채널(1순위) | 카카오 알림톡 | **텔레그램 먼저 가동** (카카오 승인 대기) | ⚠️ 변경 |
| 테스트 | JUnit5 + Testcontainers + Playwright | 동일 | ✅ |

### 3.2 기획 대비 달라진 점 및 이유

#### ① LLM 모델: 로컬 Ollama → OpenRouter (프로덕션)
- **기획**: 로컬 Ollama (비용 절감, 개인정보 보호)
- **실제**: 프로덕션은 OpenRouter `nemotron-3-super-120b-a12b:free`
- **이유**: AWS Lightsail 단일 인스턴스의 메모리/CPU 한계로 Ollama 모델 실행 불가. 7B 이상 모델은 서버 소형화 환경에서 현실적으로 운영 불가 → 클라우드 LLM API로 전환. 무료 티어 활용 (하루 50~1,000건)으로 비용 최소화.

#### ② 카카오 알림톡: 텔레그램으로 선 가동
- **기획**: 카카오 알림톡 1순위 (국내 사용자 친숙도)
- **실제**: 텔레그램 + 이메일 먼저 운영, 카카오는 대기
- **이유**: 카카오 비즈니스 채널 심사 기간(수 주~수 개월) 및 심사 불확실성. 텔레그램은 별도 심사 없이 즉시 가동 가능. 폴백 구조(카카오→텔레그램→이메일) 설계는 유지.

#### ③ Chroma 임베딩: 로컬 백필 후 서버 이관
- **기획**: 서버에서 Chroma 임베딩 직접 수행
- **실제**: 로컬 Mac에서 93,560건 백필 → tar/scp → 서버 mount
- **이유**: 서버 CPU 기준 건당 11.7초 → 전체 약 13일 소요로 비현실적. 로컬 M1 Mac 기준 건당 0.19초, 4시간 55분으로 완료.

#### ④ Stage 분석 깊이: 기획 vs 실제 분류 기준 미세 조정
- **기획**: Stage 1=룰 분류, Stage 2=LLM 분류+요약, Stage 3=RAG 검색, Stage 4=과거 주가 반응, Stage 5=재무/업황
- **실제 구현**: 큰 틀은 동일하나 Stage 3=Chroma RAG 유사공시, Stage 4=유사사례 수집+LLM 최종판단, Stage 5=DART 재무제표(fnlttSinglAcnt) 연동으로 구체화됨

#### ⑤ 분석 모델 정확도 이슈
- **기획**: 호재/악재 균형 잡힌 분류
- **실제**: qwen3:4b 모델이 NEUTRAL 편향 (약 99%) → Stage 3/4 RAG 보강으로 완화 시도 중
- **이유**: 소형 로컬 모델의 한계. 보수적 판단을 선호하는 모델 특성. nemotron-120b(프로덕션)에서 개선 확인 중.

#### ⑥ 데이터 보호 정책 강화 (사고 후 상향)
- **기획**: 표준 Docker 볼륨 관리
- **실제**: bind mount + pg_dump 자동 백업 + Data/ 디렉터리 미러 (3중 방어)
- **이유**: 2026-06-04 Docker named volume 초기화로 91,000건 데이터 손실 사고 발생. 재발 방지를 위해 데이터 보호 정책 전면 강화.

#### ⑦ FE-BE API 불일치 (발견 후 수정)
- **기획**: api_spec.md SSOT
- **실제**: api_spec.md와 BE 실제 구현 사이 12건 불일치 발견
- **이유**: 병렬 개발 시 사양 문서 업데이트 누락. 해소 후 BE 코드가 실질적 SSOT로 운영.

---

## 4. 현재 구현 상태

### 4.1 백엔드 도메인 모듈 현황

```
com.dartcommons/
├── disclosure/      ✅ 공시 수집 완료 (94,355건, 1분 폴링)
├── analysis/        ✅ Stage 1~5 전체 구현 + AnalysisOrchestrator
├── notification/    ✅ 3채널 + 3빈도 + 재시도 + 폴백
├── user/            ✅ JWT + AES-256-GCM + OAuth2 (카카오/구글/네이버)
├── stocks/          ✅ 코스피200+코스닥150 시드 + KRX 주가 배치
├── shared/          ✅ 이벤트, 유틸, JwtTokenProvider, AesGcmEncryptor
└── infrastructure/  ✅ DART/KRX/Ollama/OpenRouter/Kakao/Telegram/Mail 클라이언트
```

**Flyway 마이그레이션**: V1~V31 (31개) 완료

### 4.2 분석 파이프라인 (Stage 1~5)

| Stage | 내용 | 구현 | 티어 |
|-------|------|------|------|
| Stage 1 | 룰 기반 공시 유형 분류 (16종) + 멱등 접수번호 | ✅ | Free |
| Stage 2 | Ollama/OpenRouter LLM 감성 분류 + 3줄 요약 + 신뢰도 | ✅ | Free |
| Stage 3 | Chroma RAG 유사 공시 벡터 검색 (93,560건 임베딩) | ✅ | Pro |
| Stage 4 | 유사 공시 수집 + LLM 최종 종합 판단 | ✅ | Pro |
| Stage 5 | DART 재무제표(fnlttSinglAcnt) + financial_snapshots | ✅ | Premium |

**보안/컴플라이언스 장치**:
- `PromptGuard`: 자본시장법 위반 표현 9개 패턴 차단 ("매수 추천", "수익 보장" 등)
- 모든 LLM 응답: Zod/Java record 스키마 파싱 후 사용 (환각 방지)
- 신뢰도(`confidence`) 필드 필수 → 낮으면 "판단 보류" 표시
- 면책 문구 + 부정확함 신고 경로 동반

### 4.3 프론트엔드 26화면 현황

| Zone | 화면 | 상태 |
|------|------|------|
| W1 랜딩 | 서비스 소개, 요금제 안내 | ✅ |
| W2 온보딩 | 회원가입, 로그인, 약관, 소셜 콜백 | ✅ |
| W3 대시보드 | 보유 종목 개요, 최신 공시 피드 | ✅ (실데이터 연동) |
| W4 공시 목록 | 감성 필터, 페이지네이션, 티어 게이트 | ✅ |
| W5 공시 상세 | 분석 결과, 호재/악재 배지, 신뢰도 미터 | ✅ |
| W6 포트폴리오 | 종목 관리, 평가 손익, 암호화 저장 | ✅ |
| W7 알림/설정 | 알림 센터, 알림 설정, 빈도/채널/타입 | ✅ |

**핵심 기술 구현**:
- `authStore` (Zustand): BroadcastChannel 기반 탭 간 로그아웃 동기화
- 토큰 갱신: Promise 큐 + httpOnly 쿠키 (race condition 완전 해소)
- `useTierCheck`: Free/Pro/Premium 기능 게이팅
- `SentimentBadge`: 색상 + 텍스트 + 아이콘 병용 (WCAG 2.1 AA, 색맹 배려)
- `ConfidenceMeter`: 신뢰도 시각화

### 4.4 데이터 현황 (2026-07-07 기준)

| 테이블 | 건수 | 비고 |
|--------|------|------|
| disclosures | ~94,355건 | 90일 청크 백필 + 1분 폴링 계속 수집 |
| analysis_results | ~19,609건 | Stage 1~2 완료, Stage 3~5 진행 중 |
| Chroma 임베딩 | 93,560건 | nomic-embed-text, 서버 이관 완료 |
| stock_prices | 28,258건 | KRX 일 배치 |
| users | 운영 중 | AES-256-GCM 암호화 개인정보 |
| stocks (마스터) | 350종목 | 코스피200+코스닥150 |

### 4.5 배포 인프라

| 항목 | 상태 | 비고 |
|------|------|------|
| 서버 | ✅ | AWS Lightsail, 공인 IP 54.116.17.180 |
| 도메인/HTTPS | ✅ | gangwoncanvas.co.kr, Let's Encrypt |
| 컨테이너 | ✅ | Docker Compose (BE + FE + Postgres + Chroma + Ollama + nginx) |
| CI/CD | ✅ | GitHub Actions (빌드·테스트 자동화) |
| 데이터 보호 | ✅ | bind mount + pg_dump + Data/ 미러 (3중) |
| 모니터링 | ⚠️ | Spring Boot Actuator + 로그만, 전용 모니터링 미구축 |

### 4.6 테스트 현황

| 영역 | 내용 | 상태 |
|------|------|------|
| BE 통합 테스트 | Testcontainers PostgreSQL 기반, 79+건 | ✅ 통과 |
| BE 단위 테스트 | JUnit5 + Mockito, 100+건 | ✅ |
| FE 단위 테스트 | Vitest, 44+건 | ✅ |
| FE E2E | Playwright, 20+건 | ✅ |
| BE 빌드 | `./gradlew build` | ✅ |
| FE 타입 체크 | `pnpm typecheck --noEmit` | ✅ |

### 4.7 Spec 문서 상태

| 상태 | 건수 | 대표 항목 |
|------|------|---------|
| ✅ Done | 71건 | disclosure-collection-pipeline, analysis-stage2-llm, user-auth-jwt-oauth2, notification-dispatcher, frontend-full-ui-implementation, Stage 3/4/5, 배포 인프라 등 |
| 📋 Draft | 2건 | payment-pg-integration (카카오페이 정기결제), frontend-share-card-image (공유 카드) |
| 🔍 Approved | 0건 | — |

---

## 5. 한계점

### 5.1 기술적 한계

#### (1) LLM 분석 정확도 — 가장 중요한 한계
- **현상**: qwen3:4b 로컬 모델이 NEUTRAL 편향 (~99%). 명백한 호재/악재도 "중립" 판정.
- **원인**: 소형 모델의 보수적 특성. 한국 금융 도메인 파인튜닝 부재.
- **영향**: 서비스의 핵심 가치("호재/악재 즉시 판단")가 로컬 환경에서 제한됨.
- **현황**: 프로덕션 nemotron-120b로 개선 확인 중. Stage 3 RAG 보강 병행.

#### (2) 서버 사양 제약
- **현상**: AWS Lightsail 단일 소형 인스턴스 — Ollama 7B+ 모델 실행 불가.
- **영향**: LLM을 외부 API(OpenRouter)에 의존 → 무료 티어 하루 50~1,000건 제한.
- **결과**: 볼륨 확대 시 유료 전환 필수. 현재 무료 상한이 서비스 성장의 병목.

#### (3) Stage 3 임베딩 재실행 비용
- **현상**: 서버 CPU 기준 93k건 임베딩에 13일 소요.
- **영향**: 임베딩 모델 교체 또는 데이터 대규모 추가 시 재임베딩 불가.
- **현황**: 로컬 Mac 백필 → scp 이관 방식으로 해결했으나 자동화 미흡.

#### (4) WCAG 2.1 AA 미완성
- **현상**: 일부 인터랙티브 요소 aria-label 누락, 키보드 경로 미비.
- **영향**: 접근성 미달 → 시니어(페르소나 E) 타겟 확장 지연.

#### (5) 실시간성 제약
- **현상**: DART 폴링 1분 간격 → 공시 발생 후 최대 60초 지연.
- **영향**: 단타 트레이더(페르소나 B)의 "즉시 알림" 요구를 완전히 충족하지 못함.
- **현황**: WebSocket 실시간 전환은 미구현 상태.

### 5.2 비즈니스/운영 한계

#### (6) 카카오 알림톡 승인 대기
- **현상**: 국내 사용자의 주 알림 채널(카카오톡)을 아직 사용 불가.
- **영향**: 텔레그램을 대안으로 사용하나 사용자 친숙도 낮음.
- **리스크**: 심사 기간 불확실(수 주~수 개월), 승인 거부 가능성 존재.

#### (7) 결제 시스템 미구현
- **현상**: 기획서의 Free/Pro/Premium 3-tier 요금제는 UI에 존재하나 실제 결제 연동 없음.
- **영향**: 현재 수익화 불가 상태. 모든 사용자가 사실상 무제한 사용.
- **현황**: `payment-pg-integration` Spec 작성 완료 (Draft 상태).

#### (8) 91k 공시 전체 분석 미완료
- **현상**: 19,609건만 분석 완료 (전체 94,355건 중 약 20%).
- **영향**: 과거 패턴 분석(Stage 3/4)의 학습 데이터 부족 → RAG 품질 저하.
- **현황**: 분석 롤아웃 진행 중. 서버 LLM 한계로 속도 제한.

#### (9) KRX 실데이터 주가 검증 미완
- **현상**: KRX OpenAPI 일 배치 연동 구현 완료하였으나 실데이터 안정성 완전 검증 필요.
- **영향**: 포트폴리오 평가 손익 계산 정확도.

#### (10) 모니터링/운영 도구 부재
- **현상**: Spring Boot Actuator + 파일 로그만 존재. 전용 APM/대시보드 없음.
- **영향**: 장애 감지 지연, 이상 트래픽/오류 조기 발견 불가.

---

## 6. 앞으로의 로드맵

### 6.1 단기 (1~2주) — 서비스 완성도

| 과제 | 내용 | 우선순위 |
|------|------|--------|
| 91k 공시 전체 분석 롤아웃 | Stage 1~4 전체 적용, RAG 품질 강화 | P0 |
| 결제 PG 연동 | 카카오페이 정기결제, Free/Pro/Premium 게이팅 | P0 |
| 카카오 알림톡 재신청/확정 | 채널 확정 or 텔레그램 기본 채널화 | P1 |
| KRX 실데이터 주가 검증 | 실 종가 데이터 정확도 확인 | P1 |
| WCAG 2.1 AA 완성 | aria-label, 키보드 경로, 대비 비율 | P2 |

### 6.2 중기 (1~2개월) — 서비스 안정화 + 베타

| 과제 | 내용 | 기대 효과 |
|------|------|---------|
| 모델 정확도 개선 | RAG 강화 + 프롬프트 튜닝 + 평가 데이터셋 구축 | NEUTRAL 편향 해소 |
| 베타 사용자 테스트 (100명) | 실제 투자자 피드백 수집 | 제품-시장 적합성 검증 |
| 공유 카드 기능 | html2canvas 기반 공시 분석 카드 SNS 공유 | 바이럴 획득 (페르소나 D) |
| WebSocket 실시간 알림 | Server-Sent Events 또는 WebSocket 전환 | 60초 지연 → 10초 이하 |
| 모니터링 구축 | Grafana + Prometheus 또는 Sentry | 장애 조기 감지 |
| 공식 사용자 온보딩 | 회원가입 후 포트폴리오+알림 설정 가이드 | 리텐션 향상 |

### 6.3 장기 (3~6개월) — 스케일 & 확장

| 과제 | 내용 | 비고 |
|------|------|------|
| B2B API 출시 | 증권사·핀테크용 공시 분석 REST API | Fiscal.ai 모델 벤치마킹 |
| Stage 5 업황 데이터 고도화 | 공공 API 통합 (한국은행, 통계청) | Premium 가치 강화 |
| 글로벌 공시 확장 | 미국 SEC EDGAR, 일본 EDINET | 해외 주식 투자자 타겟 |
| 모바일 앱 (PWA → Native) | React Native 또는 Flutter | 페르소나 A/B/E 접근성 향상 |
| 시니어 UI 모드 | 큰 글자, 단순화된 용어 | 페르소나 E 전용 |
| LLM 파인튜닝 | 한국 공시 도메인 특화 모델 | 정확도 근본 해결 |

---

## 7. 고도화 방향 (심화)

### 7.1 분석 엔진 고도화

#### (1) 라벨링 데이터셋 구축
현재 분석 품질의 근본적 한계는 **평가 데이터 부재**다. `scripts/labeling/` 도구를 활용해 과거 공시-주가 반응 쌍을 라벨링하면:
- 정량적 정확도 측정 (precision/recall)
- 프롬프트 튜닝의 명확한 기준선 확보
- 장기적으로는 소형 모델 파인튜닝 가능

#### (2) Stage 4 주가 패턴 강화
현재 Stage 4는 유사 공시 수집 후 LLM 재판단에 그친다. 실제 과거 주가 데이터(t+1, t+5, t+20일)를 연계하면:
- "이 유형 공시 평균 주가 반응 -6.3%" 수치화 가능
- Pro 구독 핵심 가치 증명

#### (3) 신뢰도 보정 (Calibration)
현재 `confidence` 필드는 모델 자가 보고(self-reported)이므로 실제 정확도와 다를 수 있다. RAG 결과와의 일치도, 과거 해당 유형 공시 정확도를 결합한 보정 신뢰도로 고도화 가능.

### 7.2 사용자 경험 고도화

#### (1) 온보딩 완성
체크리스트 모달(포트폴리오+알림 설정)은 구현됐으나, "내 첫 번째 공시 해석 보기" 등 첫 경험 구체화로 리텐션 향상.

#### (2) 공시 북마크 + 메모
사용자가 특정 공시에 메모를 남기고, 나중에 주가 움직임을 회고하는 "내 투자 일지" 기능 → 재방문 동기 강화.

#### (3) 알림 커스터마이징 고도화
현재 빈도(INSTANT/DAILY/WEEKLY) + 감성(호재/악재/전체)만 지원. 공시 유형별 세분화 필터 추가 → 사용자 만족도 향상.

### 7.3 수익화 고도화

#### (1) 결제 시스템 우선 완성
`payment-pg-integration` Spec이 Draft 상태. 카카오페이 정기결제 연동은 수익화의 전제.

#### (2) 연 결제 할인 + 트라이얼
Robinhood Gold 모델처럼 월 결제 vs 연 결제 할인 구조 + 7일 Pro 무료 체험으로 전환율 최적화.

#### (3) B2B API 기반 수익 다각화
B2C 사용자 확보 후 Fiscal.ai 모델로 증권사·핀테크에 공시 분석 API 제공. 건당 과금 또는 월 구독.

### 7.4 인프라 고도화

#### (1) 서버 스케일업 또는 멀티 인스턴스
현재 Lightsail 단일 인스턴스 → 트래픽 증가 시 BE/DB/LLM 분리 또는 스케일업.

#### (2) 임베딩 자동화 파이프라인
신규 공시 분석 완료 → 자동 Chroma 임베딩 → RAG 데이터 실시간 갱신. 현재는 수동 백필 구조.

#### (3) APM/알림 구축
Grafana + Prometheus + Sentry 도입. 서비스 다운, LLM 오류율, 알림 발송 실패율 등 핵심 지표 모니터링.

---

## 8. 프로젝트 성과 요약

### 8.1 수치로 본 성과

| 지표 | 값 |
|------|-----|
| 개발 기간 | 약 6주 (2026-05-27 ~ 2026-07-07) |
| 수집 공시 건수 | 94,355건 |
| Chroma 임베딩 건수 | 93,560건 |
| 분석 완료 건수 | 19,609건 (전체의 ~20%) |
| Flyway 마이그레이션 | V1~V31 (31개) |
| Spec Done | 71건 |
| BE 테스트 | 179+ (통합 79 + 단위 100+) |
| FE 테스트 | 64+ (Vitest 44 + Playwright 20) |
| 구현 화면 수 | 26화면 (W1~W7) |

### 8.2 기술적 성취

- **완전한 5단계 분석 파이프라인** (Stage 1~5) 설계·구현·배포
- **AES-256-GCM** 매수가/수량 암호화 (금융 데이터 보호 강화)
- **PromptGuard** (자본시장법 위반 표현 자동 차단)
- **3중 데이터 보호** (사고 후 교훈으로 내재화)
- **하이브리드 RAG**: 룰 기반 + LLM + pgvector/Chroma 벡터 검색

### 8.3 프로젝트 현재 상태

```
MVP 완성도: ████████████████████░░ 90%

완료된 것
├── 공시 수집 파이프라인 (94k건)
├── LLM 분석 5단계 (Stage 1~5)
├── 사용자 인증/포트폴리오/알림 시스템
├── 26화면 프론트엔드 UI
├── Docker + CI/CD 배포 인프라
└── 프로덕션 라이브 (gangwoncanvas.co.kr)

남은 것 (1~2주)
├── 91k 전체 분석 롤아웃
├── 결제 PG 연동
└── 카카오 알림톡 채널 확정
```

---

## 9. 참고 문서

| 분류 | 경로 |
|------|------|
| 서비스 기획 SSOT | `docs/기획서/DART공시통역_통합기획서.md` |
| 모듈/시퀀스/큐 | `docs/개발명세서/feature_structure.md` |
| REST API 명세 | `docs/개발명세서/api_spec.md` |
| DB 스키마 | `docs/개발명세서/db_schema.md` |
| IA/디자인 토큰 | `docs/개발명세서/design/design_structure.md` |
| Spec 문서 | `docs/specs/{Draft,Approved,Done}/` |
| 개발 로그 | `docs/dev-log/backend.jsonl`, `docs/dev-log/frontend.jsonl` |
| 프로덕션 배포 메모 | `.claude/projects/memory/project_production_deploy.md` |
