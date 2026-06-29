-- PostgreSQL 15+ 에서 public 스키마 CREATE 권한이 기본 제거됨.
-- dartcommons 유저가 Flyway 마이그레이션을 실행할 수 있도록 권한 부여.
GRANT ALL ON SCHEMA public TO dartcommons;
