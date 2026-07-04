#!/usr/bin/env bash
# restore_db.sh — DartCommons Postgres 복원 스크립트
#
# [목적] backup_db.sh로 생성한 .sql.gz 덤프를 빈 DB에 복원.
# [전제] 컨테이너 가동 중 + Flyway 마이그레이션 미실행 빈 DB OR 동일 스키마.
#       Flyway가 이미 적용된 DB에 복원 시 충돌 — 본 스크립트는 TRUNCATE/DROP 자동 수행 안함.
# [사용]
#   bash scripts/data_protection/restore_db.sh                              # 최신 백업 복원
#   bash scripts/data_protection/restore_db.sh --file <path/to/file.sql.gz> # 특정 파일
#   bash scripts/data_protection/restore_db.sh --force                       # 기존 DB drop 후 재생성
#
# [복원 시나리오]
#   1. 백엔드 종료
#   2. (옵션) docker volume rm/bind mount 디렉토리 비우기 → fresh DB
#   3. docker compose up -d 후 Flyway 미실행 상태에서 본 스크립트 실행
#   OR
#   1. --force 옵션으로 기존 DB 일괄 재생성 후 복원

set -euo pipefail

CONTAINER="${DARTCOMMONS_PG_CONTAINER:-dartcommons-postgres}"
DB_NAME="${DB_NAME:-dartcommons}"
DB_USER="${DB_USERNAME:-dartcommons}"
DEST_DIR="${HOME}/data/dartcommons-backups"
BACKUP_FILE=""
FORCE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --file)   BACKUP_FILE="$2"; shift 2 ;;
        --force)  FORCE=true; shift ;;
        -h|--help)
            grep -E "^# " "$0" | head -25 | sed 's/^# //'
            exit 0
            ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo "ERROR: 컨테이너 '${CONTAINER}' 가 실행 중이 아님." >&2
    exit 2
fi

# 최신 백업 자동 선택
if [[ -z "$BACKUP_FILE" ]]; then
    BACKUP_FILE=$(ls -1t "${DEST_DIR}"/dartcommons_*.sql.gz 2>/dev/null | head -1 || true)
    if [[ -z "$BACKUP_FILE" ]]; then
        echo "ERROR: ${DEST_DIR}에 백업 파일이 없습니다." >&2
        exit 3
    fi
fi

if [[ ! -f "$BACKUP_FILE" ]]; then
    echo "ERROR: 백업 파일 없음: $BACKUP_FILE" >&2
    exit 4
fi

echo "[$(date +%H:%M:%S)] 복원 대상: $BACKUP_FILE"

# --force: 기존 DB 일괄 재생성 (postgres DB로 접속해 drop/create)
if [[ "$FORCE" == "true" ]]; then
    echo "[$(date +%H:%M:%S)] --force: 기존 DB drop 후 재생성 (${DB_NAME})"
    # 다른 연결 강제 종료
    docker exec "$CONTAINER" psql -U "$DB_USER" -d postgres -c \
        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='${DB_NAME}' AND pid <> pg_backend_pid();" >/dev/null
    docker exec "$CONTAINER" psql -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS ${DB_NAME};"
    docker exec "$CONTAINER" psql -U "$DB_USER" -d postgres -c "CREATE DATABASE ${DB_NAME};"
fi

# 복원
echo "[$(date +%H:%M:%S)] gunzip + psql 복원 시작"
gunzip -c "$BACKUP_FILE" | docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" >/dev/null

# 검증
DISCLOSURES=$(docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT COUNT(*) FROM disclosures;" 2>/dev/null || echo "?")
STOCKS=$(docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT COUNT(*) FROM stocks;" 2>/dev/null || echo "?")
echo "[$(date +%H:%M:%S)] 복원 완료 — disclosures=${DISCLOSURES} stocks=${STOCKS}"
