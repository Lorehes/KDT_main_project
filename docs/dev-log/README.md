---
type: doc
status: active
created: 2026-05-28
updated: 2026-05-28
---

# 변경 로그 (dev-log)

> 코드 변경마다 해당 JSONL에 1줄 추가한다. (CLAUDE.md §6-2)
> - 백엔드 변경 → `backend.jsonl`
> - 프론트 변경 → `frontend.jsonl`

## JSONL 스키마

```jsonc
{
  "date":    "YYYY-MM-DD",           // 변경 날짜
  "session": "세션 식별자 (선택)",
  "spec":    "docs/specs/.../slug.md", // 관련 Spec (상대경로, 없으면 null)
  "summary": "변경 요약 1줄",
  "why":     "변경 이유 (티켓/요구사항/버그)",
  "files":   [                        // 변경된 파일 (root 기준 상대경로)
    "backend/src/main/java/com/dartcommons/..."
  ],
  "stage":   "implement|fix|refactor|test|chore",
  "impact":  "변경 영향 범위 (선택)"
}
```

## 규칙

- `files` 필드는 반드시 **root 기준 상대경로**
- 코드 주석 = "코드 자체"의 컨텍스트, 변경 로그 = "변경의 컨텍스트" — 둘은 별개
- 기계 판독 파일이므로 frontmatter 없음

## 파일

- `backend.jsonl` — 백엔드 변경 이력
- `frontend.jsonl` — 프론트엔드 변경 이력
