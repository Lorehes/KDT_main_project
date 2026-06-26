---
type: moc
status: active
created: 2026-05-28
updated: 2026-06-22
---

# DART 공시 통역 (DartCommons) — HOME

> Vault 진입점. 모든 주요 노트로 연결.
> 보유 종목의 DART 공시를 실시간으로 받아 호재/악재 의미를 자연어로 해석해주는 개인 투자자용 AI 알림 서비스.

---

## 핵심 문서

- [[CLAUDE]] — 프로젝트 컨텍스트 (Claude 자동 로드)
- [[DART공시통역_통합기획서]] — 서비스 기획 SSOT

## 개발 명세 (`docs/개발명세서/`)

- [[api_spec]] — REST API + DART/KRX/공공 OpenAPI 명세
- [[db_schema]] — DB 스키마 (PostgreSQL) + Chroma 컬렉션
- [[feature_structure]] — 모듈/시퀀스/큐 운영 설계
- [[design_structure]] — IA/디자인 토큰 (Next.js + shadcn/ui)

→ [[docs/개발명세서/README|개발명세서 MOC]]

## Specs (`docs/specs/`)

- [[docs/specs/README|Specs MOC]] — Draft / Approved / Done 현황
- [[docs/specs/Approved/topbar-global-search|TopBar 글로벌 검색]] — Approved · TopBar Enter → /disclosures?q= 라우팅, BE ILIKE 추가
- [[docs/specs/Done/portfolio-csv-bulk-import|포트폴리오 CSV 일괄 등록]] — Done · POST /portfolios/import 벌크 엔드포인트, FE N루프 → 단일 호출 (2026-06-26)

## 프로젝트 일정

- [[docs/milestone-2026-mvp-launch|MVP 런치 마일스톤]] — 2026-06-16 ~ 07-03 배포 일정 · Go/No-Go 기준

## 운영 문서

- [[docs/dev-log/README|변경 로그]] — `docs/dev-log/{backend,frontend}.jsonl`
- [[docs/ideas/README|아이디어/피드백]] — `docs/ideas/`
- [[docs/git-history/README|Git 히스토리]] — `docs/git-history/`

## 이슈 (`docs/issues/`)

- [[docs/issues/portfolio-add-switch-overflow|Switch 오버플로 false-positive]] — P3 · /portfolios/add 토글 행 scrollWidth 11px (after 터치 영역 확장)
- [[docs/issues/portfolio-csv-upload|CSV 업로드 미구현]] — P2 · /portfolios/new 증권사 거래내역 업로드 (기획서 1순위)
- [[docs/issues/topbar-settings-frontend-tech-debt|TopBar·Settings 기술 부채]] — P2 · TIER_LABEL 중복·Zustand 셀렉터·AppShell 이중 마운트
- [[docs/issues/topbar-global-search|TopBar 글로벌 검색]] — 미구현
- [[docs/issues/public-navbar-aria-labels|PublicNavbar aria-labels]] — 접근성
- [[docs/issues/public-layout-dynamic-rendering-perf|PublicLayout 동적 렌더링 성능]] — SSR 최적화

---

## 기술 스택 요약

| 계층 | 스택 |
|------|------|
| Backend | Java 21 + Spring Boot 3.x |
| Frontend | Next.js 15 + TypeScript + TailwindCSS 4 + shadcn/ui |
| DB | PostgreSQL + Flyway |
| Vector DB | Chroma (RAG Stage 3) |
| LLM | LangChain4j 1.x (MVP: Ollama / 실서비스: Cloud) |
| Auth | Spring Security + JWT + OAuth2 (Kakao/Google/Naver) |
| 알림 | 카카오 알림톡 → 텔레그램/이메일 폴백 |

## 분석 Stage (티어별 차등)

| Stage | 내용 | Free | Pro | Premium |
|-------|------|------|-----|---------|
| 1 | 룰 기반 추출 | ✓ | ✓ | ✓ |
| 2 | LLM 분류 | ✓ | ✓ | ✓ |
| 3 | RAG 검색 | - | ✓ | ✓ |
| 4 | LLM 최종 | - | ✓ | ✓ |
| 5 | 재무/업황 | - | - | ✓ |

---

## 작업 흐름 (Skills)

```
/dc-plan → /dc-tech-review → /dc-implement → /dc-review-code → /dc-test-verify → /dc-push → /dc-doc-sync
```
