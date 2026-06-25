---
type: spec
status: Draft
created: 2026-06-25
updated: 2026-06-25
---

# 배포 인프라 Spec (Docker·클라우드·CI/CD)

> 상태: **Draft** (dc-plan 생성)
> 선행 Spec: [[llm-production-switch]] — Ollama 컨테이너 구성과 연계
> 관련 마일스톤: M4 (6/25~6/29) — 크리티컬 패스

## 배경 / 목적

- **문제**: `Dockerfile`(BE/FE), `docker-compose.prod.yml`, Nginx 리버스 프록시, CI/CD 파이프라인이 전무. 프로덕션 배포 불가 상태.
- **해결**: BE·FE Docker 이미지 빌드 → 클라우드(AWS EC2) 배포 → GitHub Actions 자동화 → HTTPS 도메인 접속 가능 상태로 만든다.
- **Go/No-Go 의존**: 7/3 런치 Go/No-Go 기준 "HTTPS 프로덕션 도메인 접속 가능", "헬스체크 `/actuator/health` OK" 두 항목 모두 본 Spec 완료 후 충족.

## 현황 분석

```
프로젝트 루트/
├── docker-compose.yml         ← 로컬 개발용 PostgreSQL only (bind mount 적용됨)
├── backend/                   ← Dockerfile 없음
├── frontend/                  ← Dockerfile 없음
└── .github/                   ← workflows/ 없음
```

로컬 `docker-compose.yml`의 bind mount(`${PG_DATA_DIR:-~/data/dartcommons-pg}`) 패턴은 프로덕션에도 **반드시 유지** (2026-06-04 named volume 91k건 손실 사고 교훈 — [[data_protection]]).

## 요구사항

### 4-1. Docker 이미지 빌드

- [ ] **R1** `backend/Dockerfile` — Spring Boot 멀티스테이지 빌드
  - Stage 1 `builder`: `eclipse-temurin:21-jdk-alpine` + Gradle 빌드
  - Stage 2 `runtime`: `eclipse-temurin:21-jre-alpine` + JAR 복사
  - 비루트 유저(`appuser`) 실행
  - 포트 `8080` EXPOSE
- [ ] **R2** `frontend/Dockerfile` — Next.js standalone 빌드
  - `node:22-alpine` + `pnpm install --frozen-lockfile` + `pnpm build`
  - `output: 'standalone'` (next.config.ts 확인 필요)
  - 비루트 유저 실행, 포트 `3000` EXPOSE
- [ ] **R3** `docker-compose.prod.yml` — 프로덕션 서비스 정의
  - 서비스: `postgres`, `backend`, `frontend`, `ollama`(선택 — [[llm-production-switch]])
  - PostgreSQL bind mount: `/home/ubuntu/data/dartcommons-pg:/var/lib/postgresql/data`
  - 환경변수: `.env.prod` 파일 주입 (`env_file: .env.prod`)
  - `restart: unless-stopped` 전체 서비스
  - 내부 네트워크 격리 (`networks: dartcommons-net`)
- [ ] **R4** `.env.prod.template` — 프로덕션 환경변수 템플릿 (값 제외, 키만 명시)
  - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
  - `JWT_SECRET`, `JWT_REFRESH_SECRET`
  - `DART_API_KEY`, `KRX_API_KEY`
  - `MAIL_*`, `KAKAO_*` (알림톡·OAuth)
  - `LLM_PROVIDER`, `OLLAMA_BASE_URL`, `LLM_MODEL`
  - `NEXT_PUBLIC_API_BASE_URL`

### 4-2. 클라우드 인프라 (AWS EC2)

- [ ] **R5** EC2 인스턴스 프로비저닝
  - **타입**: t3.large 이상 (Ollama qwen3:4b ≈ 3~4GB RAM, BE 1GB, FE 512MB → 최소 6GB 필요)
  - OS: Ubuntu 24.04 LTS
  - 스토리지: 30GB gp3 (PostgreSQL 데이터 + Docker 이미지)
  - 보안그룹: 인바운드 80/443(0.0.0.0/0), 22(내 IP only). **DB 5432 외부 오픈 금지**
- [ ] **R6** Docker + Docker Compose 설치 (EC2 초기화 스크립트)
- [ ] **R7** 도메인·SSL 설정
  - 도메인 구매 또는 기존 도메인 활용 (CloudFlare 권장 — 무료 SSL)
  - CloudFlare DNS → EC2 탄력적 IP 연결
  - 또는 AWS Route53 + ACM(인증서) + ALB
- [ ] **R8** Nginx 리버스 프록시 (`/etc/nginx/sites-available/dartcommons`)
  - `/api/` → `http://localhost:8080` (BE)
  - `/` → `http://localhost:3000` (FE)
  - HTTPS 강제 redirect (80 → 443)
  - SSL 인증서 연결
- [ ] **R9** 방화벽 확인 — 80/443만 외부 오픈, 나머지 차단

### 4-3. CI/CD (GitHub Actions)

- [ ] **R10** `.github/workflows/deploy.yml`
  - 트리거: `main` 브랜치 push
  - Jobs:
    1. `test`: `./gradlew test` + `pnpm test`
    2. `build-and-push`: Docker 이미지 빌드 → Docker Hub 또는 GHCR 푸시
    3. `deploy`: SSH로 EC2 접속 → `docker compose pull` → `docker compose up -d`
- [ ] **R11** GitHub Secrets 등록
  - `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`
  - `DOCKER_USERNAME`, `DOCKER_PASSWORD` (또는 GHCR `GITHUB_TOKEN`)
- [ ] **R12** Flyway 자동 마이그레이션 확인 — BE 기동 시 `ddl-auto: validate` + Flyway 자동 적용 (현재 V23까지 적용됨, 다음은 V24)
- [ ] **R13** 헬스체크 Step — 배포 후 `/actuator/health` 폴링 (최대 60초, 실패 시 롤백 알림)

### 4-4. 스테이징 검증

- [ ] **R14** 프로덕션 동일 환경에서 회원가입 → 공시 조회 → 분석 확인 → 알림 수신 전체 플로우 통과
- [ ] **R15** `docs/운영가이드.md` 업데이트 — 배포 절차·Ollama 모델 pull·DB 백업 방법

## 영향 범위 (조사 결과)

- **영향 레이어**: infra(신규 파일들), backend(Dockerfile), frontend(Dockerfile), CI(GitHub Actions)
- **신규 파일**:
  - `backend/Dockerfile`
  - `frontend/Dockerfile`
  - `docker-compose.prod.yml`
  - `.env.prod.template`
  - `.github/workflows/deploy.yml`
  - `nginx/dartcommons.conf` (또는 EC2 직접 설정)
- **수정 파일**:
  - `frontend/next.config.ts` — `output: 'standalone'` 추가 여부 확인
  - `docs/운영가이드.md`
- **DB 변경**: 없음 (Flyway 기존 V23 그대로)
- **외부 계약**: AWS EC2 + 도메인 등록 + CloudFlare/Route53

## 관련 패턴 / 과거 사례

- 로컬 `docker-compose.yml` — bind mount 패턴(`${PG_DATA_DIR:-~/data/...}`) 그대로 프로덕션 적용. named volume 절대 금지 ([[data_protection]]).
- `backend/src/main/resources/application.yml` — `ddl-auto: validate`, Flyway 자동 적용 설정 이미 구성됨.
- `docs/개발명세서/db_schema.md` — V23까지 마이그레이션 이력 확인, 다음 V24.

## 리스크 / 법적 검토

| 리스크 | 발생 확률 | 영향 | 대응 |
|------|---------|------|------|
| EC2 메모리 부족 (Ollama + BE + FE + PG) | 高 | 高 | t3.large(8GB) 이상 선택. Ollama 없이 시작 후 추가 가능 |
| DB 마이그레이션 실패 (프로덕션 첫 적용) | 低 | 高 | 스테이징 먼저 Flyway dry-run. 백업 후 적용 |
| 도메인/SSL 발급 지연 | 低 | 中 | CloudFlare 무료 인증서는 즉시 발급. ACM은 DNS 검증 ~30분 |
| 이미지 빌드 실패 (의존성 충돌) | 中 | 中 | 로컬에서 `docker build` 선검증 후 CI 연동 |
| `.env.prod` 시크릿 유출 | 절대 방지 | 高 | `.gitignore`에 `.env.prod` 명시. GitHub Secrets으로만 주입. 코드/로그 하드코딩 금지 (CLAUDE.md §7) |
| 매수가·보유량 평문 로깅 | 절대 방지 | 高 | 배포 전 로그 패턴 점검 (CLAUDE.md §7) |

## 권장 구현 방향

**단계 순서**:
1. `backend/Dockerfile` + `frontend/Dockerfile` 로컬 빌드 검증
2. `docker-compose.prod.yml` 로컬에서 `docker compose -f docker-compose.prod.yml up` 검증
3. EC2 프로비저닝 + Docker 설치
4. `.env.prod` 서버 직접 생성 (git에 포함 금지)
5. `docker compose -f docker-compose.prod.yml up -d` 첫 수동 배포
6. Nginx + SSL 설정
7. GitHub Actions CI/CD 연동 (이후 자동화)

**서버 선택 결정**:
- Ollama 포함 시 → **t3.large(8GB, ~$60/월)** 이상
- Cloud LLM 전환 시 → t3.medium(4GB, ~$30/월) 가능

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
