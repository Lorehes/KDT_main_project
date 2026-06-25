---
type: issue
status: Closed
severity: medium
created: 2026-06-22
updated: 2026-06-25
resolved: 2026-06-25
source: 사용자 발견 (TopBar 검색 미구현)
---

> **상태**: Closed — 2026-06-25 Spec 생성 완료.
> `docs/specs/Draft/topbar-global-search.md` 작성됨. 다음 단계: `/dc-tech-review topbar-global-search`

# TopBar 글로벌 검색 — 미구현 제거 및 후속 구현 과제

> 상태: **open** (검색 바 제거됨, 구현은 별도 Spec으로)

## 배경

`TopBar.tsx`에 "종목명 또는 공시 검색" `<input>`이 존재했으나 완전한 미구현 상태였음.
- `onChange` 없음 — 입력 무반응
- 상태 없음 — 검색어 관리 불가
- 라우팅 없음 — 결과 페이지 없음
- API 연결 없음

사용자 혼란 방지를 위해 2026-06-22 제거.

## 구현 시 필요한 범위

### 검색 대상
- **종목명**: 보유 종목·관심 종목 내 검색 (예: "삼성전자")
- **공시**: 제목·회사명으로 필터링 (예: "유상증자")

### 예상 구현 스택
| 항목 | 방식 |
|------|------|
| 트리거 | `onKeyDown Enter` 또는 debounce 300ms auto-search |
| 라우팅 | `/disclosures?q={keyword}` or `/search?q={keyword}` |
| BE API | `GET /disclosures?q=...` (기존 엔드포인트 확장) 또는 신규 `/search` |
| FE 상태 | URL searchParams 기반 (TanStack Query `searchParams` 연동) |
| 결과 표시 | 드롭다운 미리보기 or 전용 결과 페이지 |

### 접근성
- `role="combobox"`, `aria-expanded`, `aria-autocomplete` 필요
- 키보드 탐색: 방향키 + Enter + Esc

## 관련 파일

- `frontend/src/components/layout/TopBar.tsx` — 검색 바가 있던 위치
- `frontend/src/app/(app)/disclosures/page.tsx` — 기존 필터 로직 (재사용 가능)
- `frontend/src/lib/api/disclosures.ts` — `q` 파라미터 확장 필요 여부 확인
- `backend/.../disclosure/controllers/DisclosureController.java` — BE 검색 엔드포인트

## 구현 진입점

준비됐을 때 `/dc-plan TopBar 글로벌 검색 — 종목명·공시 키워드 검색 기능 구현` 으로 시작.
