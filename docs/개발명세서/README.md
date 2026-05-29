---
type: moc
status: active
created: 2026-05-28
updated: 2026-05-30
---

# 개발명세서 MOC

> 상세 기술 명세 문서 모음. 구현 시 반드시 이 문서를 먼저 확인한다.

## 문서 목록

| 파일 | 내용 | 상태 |
|------|------|------|
| [[api_spec]] | REST API + DART/KRX/공공 OpenAPI 명세 | 초안 |
| [[db_schema]] | DB 스키마 (PostgreSQL) + Chroma 컬렉션 | 초안 |
| [[feature_structure]] | 모듈/시퀀스/큐 운영 설계 | 초안 |
| [[design_structure]] | IA/디자인 토큰 (Next.js + shadcn/ui) | 초안 |

## 참고

- DART OpenAPI 호출 규칙 → [[CLAUDE]] §4
- DB 마이그레이션 → `backend/src/main/resources/db/migration/`
- 외부 키는 환경변수로만 주입 (`DART_API_KEY` 등)
