#!/usr/bin/env bash
# stage2_smoke_test.sh — Stage 2 LLM (qwen3:4b) 실 공시 5건 분석 성능/품질 측정
#
# [목적] analysis-stage2-llm Spec Tech Review T16 — wave 2 모델 후보 비교 데이터 첨부.
#       프로덕션 백엔드 통하지 않고 Ollama API 직접 호출 + DB에서 실 공시 메타 추출.
# [사용] bash scripts/analysis/stage2_smoke_test.sh [--model qwen3:4b]
# [출력] 표준출력 + docs/dev-log/analysis-stage2-smoke.md (수동 갱신 권장)

set -euo pipefail

# macOS locale — 한글 tr/sed 처리
export LC_ALL=en_US.UTF-8
export LANG=en_US.UTF-8

MODEL="${1:-qwen3:4b}"
OLLAMA="${OLLAMA_BASE_URL:-http://localhost:11434}"
CONTAINER="${DARTCOMMONS_PG_CONTAINER:-dartcommons-postgres}"
DB_USER="${DB_USERNAME:-dartcommons}"
DB_NAME="${DB_NAME:-dartcommons}"

PROMPT_TEMPLATE='당신은 한국 DART 공시 분석 어시스턴트입니다. 정보 제공 목적이며 투자 자문이 아닙니다. 다음 공시를 호재/중립/악재로 분류하세요. JSON으로만 응답.

공시:
- 회사명: %CORP_NAME%
- 보고서 제목: %REPORT_NM%
- 분류값: %TYPE%
- 접수일: %RCEPT_DT%

스키마:
{"sentiment": "POSITIVE|NEUTRAL|NEGATIVE", "confidence": 0.0~1.0 실수, "summary": "3줄 한국어 요약"}'

echo "=== Stage 2 Smoke Test ==="
echo "model: $MODEL"
echo "ollama: $OLLAMA"
echo ""

# 다양한 유형 5건 (오늘치)
QUERY="SELECT id || E'\t' || corp_name || E'\t' || report_nm || E'\t' || disclosure_type || E'\t' || rcept_dt
       FROM disclosures
       WHERE disclosure_type IN ('TREASURY_STOCK','RIGHTS_OFFERING','CAPITAL_REDUCTION','EARNINGS_PRELIMINARY','MERGER')
       ORDER BY rcept_dt DESC LIMIT 5;"

ROWS=$(docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$QUERY")

TOTAL_MS=0
COUNT=0
echo "| # | corp | report (cut) | type | senti | conf | dur(ms) | summary(80자) |"
echo "|---|------|--------------|------|-------|------|---------|---------------|"

while IFS=$'\t' read -r id corp report type rcept_dt; do
    [[ -z "$id" ]] && continue
    PROMPT=$(echo "$PROMPT_TEMPLATE" \
        | sed "s|%CORP_NAME%|${corp}|" \
        | sed "s|%REPORT_NM%|${report}|" \
        | sed "s|%TYPE%|${type}|" \
        | sed "s|%RCEPT_DT%|${rcept_dt}|")

    BODY=$(jq -n --arg m "$MODEL" --arg p "$PROMPT" '{
        model: $m, prompt: $p, format: "json", stream: false, think: false,
        options: {temperature: 0.2, num_predict: 400}
    }')

    RES=$(curl -s "$OLLAMA/api/generate" -d "$BODY")
    RESPONSE=$(echo "$RES" | jq -r '.response // ""')
    DUR_NS=$(echo "$RES" | jq -r '.total_duration // 0')
    DUR_MS=$(( DUR_NS / 1000000 ))
    EVAL=$(echo "$RES" | jq -r '.eval_count // 0')

    # 응답 파싱
    SENT=$(echo "$RESPONSE" | jq -r '.sentiment // "?"' 2>/dev/null || echo "PARSE_FAIL")
    CONF=$(echo "$RESPONSE" | jq -r '.confidence // 0' 2>/dev/null || echo "0")
    SUMMARY=$(echo "$RESPONSE" | jq -r '.summary // ""' 2>/dev/null || echo "")
    # Python으로 안전 truncate (한글 멀티바이트 보호)
    SUMM_SHORT=$(printf "%s" "$SUMMARY" | python3 -c "import sys; t=sys.stdin.read().replace('|','').replace('\n',' '); print(t[:80])")
    REPORT_SHORT=$(printf "%s" "$report" | python3 -c "import sys; t=sys.stdin.read().replace('|',''); print(t[:30])")

    echo "| $((COUNT+1)) | $corp | $REPORT_SHORT | $type | $SENT | $CONF | $DUR_MS | $SUMM_SHORT |"

    TOTAL_MS=$(( TOTAL_MS + DUR_MS ))
    COUNT=$(( COUNT + 1 ))
done < <(printf "%s\n" "$ROWS")

echo ""
if [[ "$COUNT" -gt 0 ]]; then
    AVG=$(( TOTAL_MS / COUNT ))
    echo "${COUNT}건, 평균 ${AVG}ms (기획서 §6.3 목표 3000~15000ms)"
fi
