#!/usr/bin/env bash
# backup_db.sh — DartCommons Postgres 백업 스크립트
#
# [목적] dartcommons docker postgres를 pg_dump으로 압축 백업.
#       2026-06-04 데이터 손실 사고(Docker volume 통째 소실, 91,965건 → 0건) 재발 방지.
# [이유] Named volume은 Docker Desktop reset/Clean/purge 시 일괄 삭제됨.
#       bind mount 전환과 별개로 정기 dump 백업이 데이터 보호 최후 안전망.
# [사용]
#   bash scripts/data_protection/backup_db.sh                          # 기본 백업
#   bash scripts/data_protection/backup_db.sh --keep 30                # 보관 기간 조정
#   bash scripts/data_protection/backup_db.sh --dest <path>            # 저장 위치 변경
#   bash scripts/data_protection/backup_db.sh --mirror <path>          # 추가 미러 위치 1개
#
# [crontab 등록 예 (일 1회 새벽 3시) — 레포 Data/에 미러 동봉]
#   0 3 * * * /bin/bash /Users/jin/Documents/Dev/KDT_mainPJ/scripts/data_protection/backup_db.sh --mirror /Users/jin/Documents/Dev/KDT_mainPJ/Data >> ~/data/dartcommons-backups/cron.log 2>&1
#
# [기본 미러] 환경변수 BACKUP_MIRROR_DIR이 설정돼있으면 명령행 --mirror 미지정 시에도 동작.

set -euo pipefail

# --- 기본값 ---
CONTAINER="${DARTCOMMONS_PG_CONTAINER:-dartcommons-postgres}"
DB_NAME="${DB_NAME:-dartcommons}"
DB_USER="${DB_USERNAME:-dartcommons}"
DEST_DIR="${HOME}/data/dartcommons-backups"
MIRROR_DIR="${BACKUP_MIRROR_DIR:-}"
KEEP_LAST=14

# --- 인자 파싱 ---
while [[ $# -gt 0 ]]; do
    case $1 in
        --keep)   KEEP_LAST="$2"; shift 2 ;;
        --dest)   DEST_DIR="$2";  shift 2 ;;
        --mirror) MIRROR_DIR="$2"; shift 2 ;;
        -h|--help)
            grep -E "^# " "$0" | head -25 | sed 's/^# //'
            exit 0
            ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

mkdir -p "$DEST_DIR"

# --- 컨테이너 존재 확인 ---
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo "ERROR: 컨테이너 '${CONTAINER}' 가 실행 중이 아님. docker compose up -d 먼저 실행." >&2
    exit 2
fi

# --- 백업 실행 ---
TS="$(date +%Y%m%d_%H%M%S)"
OUT="${DEST_DIR}/dartcommons_${TS}.sql.gz"

echo "[$(date +%H:%M:%S)] pg_dump 시작 — container=${CONTAINER} db=${DB_NAME}"
docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" \
    --no-owner --no-acl --format=plain \
    | gzip -9 > "$OUT"

SIZE=$(du -h "$OUT" | cut -f1)
DISCLOSURES=$(docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT COUNT(*) FROM disclosures;" 2>/dev/null || echo "?")
echo "[$(date +%H:%M:%S)] 백업 완료 — ${OUT} (${SIZE}, disclosures=${DISCLOSURES})"

# --- 미러 복사 (옵션) ---
if [[ -n "$MIRROR_DIR" ]]; then
    mkdir -p "$MIRROR_DIR"
    cp -p "$OUT" "$MIRROR_DIR/"
    MIRROR_OUT="${MIRROR_DIR}/$(basename "$OUT")"
    echo "[$(date +%H:%M:%S)] 미러 복사 완료 — ${MIRROR_OUT}"
    # 미러 위치에도 동일한 보관 정책 적용
    M_COUNT=$(ls "${MIRROR_DIR}"/dartcommons_*.sql.gz 2>/dev/null | wc -l | tr -d ' ')
    if [[ "$M_COUNT" -gt "$KEEP_LAST" ]]; then
        M_REMOVE=$(( M_COUNT - KEEP_LAST ))
        ls -1t "${MIRROR_DIR}"/dartcommons_*.sql.gz | tail -n "$M_REMOVE" | xargs rm -f
        echo "[$(date +%H:%M:%S)] 미러 보관 정책 — ${M_REMOVE}개 정리 (keep=${KEEP_LAST})"
    fi
fi

# --- 오래된 백업 정리 ---
COUNT_BEFORE=$(ls "${DEST_DIR}"/dartcommons_*.sql.gz 2>/dev/null | wc -l | tr -d ' ')
if [[ "$COUNT_BEFORE" -gt "$KEEP_LAST" ]]; then
    REMOVE=$(( COUNT_BEFORE - KEEP_LAST ))
    ls -1t "${DEST_DIR}"/dartcommons_*.sql.gz | tail -n "$REMOVE" | xargs rm -f
    echo "[$(date +%H:%M:%S)] 보관 정책 — ${REMOVE}개 오래된 백업 삭제 (keep=${KEEP_LAST})"
fi

ls -lh "${DEST_DIR}"/dartcommons_*.sql.gz | tail -5
