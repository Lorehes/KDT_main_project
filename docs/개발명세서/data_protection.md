---
type: doc
status: active
created: 2026-06-04
updated: 2026-06-04
related:
  - "[[CLAUDE]]"
  - "[[db_schema]]"
---

# 데이터 보호 운영 가이드

> **근거**: 2026-06-04 데이터 손실 사고 — Docker named volume이 통째로 삭제되어 disclosures 91,965건 + stocks 341건 + flyway_schema_history 전부 소실. 3년치 백필 재실행으로 복구하는데 약 5시간 소요.
> **목표**: 동일 사고 재발 차단 + 사고 발생 시 5분 내 복구.

---

## 1. 데이터 보호 3중 방어

| 계층 | 메커니즘 | 무엇으로부터 보호 |
|------|---------|-------------------|
| **1. Bind mount** | `~/data/dartcommons-pg`를 컨테이너에 마운트 | Docker Desktop reset / "Clean / Purge data" / `down -v` / named volume 삭제 |
| **2. 자동 백업** | `scripts/data_protection/backup_db.sh` (pg_dump → gzip) | bind mount 디렉토리 자체 삭제 / 디스크 손상 / 실수 DROP TABLE |
| **3. 외부 백업** | 외장 SSD/클라우드(선택) | 노트북 분실/파손 |

---

## 2. Bind mount 위치

```yaml
# docker-compose.yml
volumes:
  - ${PG_DATA_DIR:-${HOME}/data/dartcommons-pg}:/var/lib/postgresql/data
```

- 기본 위치: `~/data/dartcommons-pg`
- 변경 시: `.env`에 `PG_DATA_DIR=/원하는/경로` 추가
- **레포 내부 경로 금지** — `git clean`/`rm -rf` 사고 위험

---

## 3. 백업 운영

### 즉시 백업

```bash
bash scripts/data_protection/backup_db.sh
# 출력: ~/data/dartcommons-backups/dartcommons_YYYYMMDD_HHMMSS.sql.gz
```

### 미러 복사 (다중 위치 보관)

`--mirror <path>` 옵션 또는 환경변수 `BACKUP_MIRROR_DIR`로 추가 위치 1개에 동일 백업본을 복제한다. 미러 위치에도 동일한 보관 정책(기본 14개)이 적용된다.

```bash
# 레포 내 Data/에 미러 (gitignore의 `data/` 규칙으로 자동 제외, macOS 대소문자 미구분)
bash scripts/data_protection/backup_db.sh --mirror /Users/jin/Documents/Dev/KDT_mainPJ/Data
```

### crontab 등록 (일 1회 새벽 3시, 미러 포함)

```cron
0 3 * * * /bin/bash /Users/jin/Documents/Dev/KDT_mainPJ/scripts/data_protection/backup_db.sh --mirror /Users/jin/Documents/Dev/KDT_mainPJ/Data >> ~/data/dartcommons-backups/cron.log 2>&1
```

설치:

```bash
crontab -e
# 위 라인 추가
crontab -l   # 확인
```

### 보관 정책

기본 14개 보관. 변경: `bash backup_db.sh --keep 30`

### 백업 위치 변경

기본 `~/data/dartcommons-backups`. 변경: `bash backup_db.sh --dest /외장/경로`

---

## 4. 복원 시나리오

### 시나리오 A: 데이터 일부 손상 / 롤백 필요

```bash
# 1. 백엔드 종료
pkill -f DartcommonsApplication

# 2. 가장 최근 백업으로 강제 복원 (기존 DB drop + 재생성)
bash scripts/data_protection/restore_db.sh --force

# 3. 특정 백업 지정
bash scripts/data_protection/restore_db.sh --file ~/data/dartcommons-backups/dartcommons_20260604_225551.sql.gz --force

# 4. 백엔드 재시작
cd backend && ./gradlew bootRun
```

### 시나리오 B: bind mount 디렉토리 통째 소실

```bash
# 1. 백엔드/컨테이너 정지
pkill -f DartcommonsApplication
docker compose down

# 2. bind mount 디렉토리 재생성
mkdir -p ~/data/dartcommons-pg

# 3. 컨테이너 재시작 (빈 DB)
docker compose up -d
# Postgres healthy 대기
until docker exec dartcommons-postgres pg_isready -U dartcommons; do sleep 1; done

# 4. 최신 백업 복원
bash scripts/data_protection/restore_db.sh

# 5. 백엔드 재시작 (Flyway validate만 수행)
cd backend && ./gradlew bootRun
```

### 시나리오 C: 백업도 손실 (최후)

3년치 백필 재실행:

```bash
ADMIN_PASS=$(grep "^ADMIN_PASSWORD" .env | cut -d= -f2-)
TODAY=$(date +%Y-%m-%d)
THREE_YEARS_AGO=$(date -v-3y +%Y-%m-%d 2>/dev/null || date -d '3 years ago' +%Y-%m-%d)
curl -u "admin:$ADMIN_PASS" -X POST \
  "http://localhost:8080/admin/disclosures/backfill/jobs?from=${THREE_YEARS_AGO}&to=${TODAY}&emitEvents=false"
# jobId 받아서 진행률 폴링
```

소요 시간: 약 3~5시간 (DART rate-limit + chunk 90일 × ~13개).

---

## 5. 절대 금지 명령

| 명령 | 영향 | 대안 |
|------|------|------|
| `docker compose down -v` | bind mount는 안전하지만, 과거 named volume도 함께 삭제 | `docker compose down` (volume 보존) |
| `docker volume prune` | 미사용 volume 일괄 삭제 | 개별 `docker volume rm <name>` 명시 |
| `docker system prune --volumes` | 모든 미사용 자원 + 볼륨 | `docker system prune` (volume 제외) |
| Docker Desktop "Reset to factory defaults" | **모든 컨테이너/볼륨/이미지 삭제** — 본 사고의 추정 원인 | 절대 사용 금지. 재설치 필요 시 사전 백업 |
| Docker Desktop "Clean / Purge data" | 위와 동일 | 절대 사용 금지 |
| `rm -rf ~/data/dartcommons-pg` | bind mount 데이터 직접 삭제 | 사전 백업 + 복원 시나리오 B |

---

## 6. 사고 발생 시 체크리스트

1. 백엔드 즉시 종료 (`pkill -f DartcommonsApplication`) — 추가 손상 방지
2. `ls ~/data/dartcommons-pg/` — bind mount 살아있는지 확인
3. `docker volume ls` — named volume 이름 확인
4. `ls -la ~/data/dartcommons-backups/` — 백업 목록 + 가장 최근 시각
5. 가장 최근 백업으로 복원 (위 시나리오)
6. 백업도 손실: 시나리오 C(3년치 백필)

---

## 7. 정기 점검 (월 1회 권장)

- [ ] 백업 파일 1개를 별도 위치(외장/클라우드)에 복사
- [ ] `restore_db.sh --file <오래된 백업>` 으로 복원 테스트 (별도 빈 컨테이너에)
- [ ] crontab 정상 실행 확인: `tail ~/data/dartcommons-backups/cron.log`
- [ ] 백업 파일 무결성: `gunzip -t ~/data/dartcommons-backups/*.sql.gz`

---

## 관련 문서

- [[db_schema]] — 테이블 구조 (복원 후 검증 대상)
- [[CLAUDE]] §6-3 — Flyway 마이그레이션 규칙
- `scripts/data_protection/backup_db.sh` / `restore_db.sh` — 스크립트 본체
