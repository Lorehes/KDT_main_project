---
type: moc
status: active
created: 2026-05-28
updated: 2026-05-30
---

# Specs MOC

> 기능 명세(Spec) 문서 현황. 상태별 폴더로 관리.
> 상태 전환은 `/dc-spec-move <slug> <상태>` 사용.

## 폴더 구조

```
docs/specs/
├── Draft/      ← 작성 중 / 검토 전
├── Approved/   ← 기술 검토 완료, 구현 가능
└── Done/       ← 구현 + 테스트 완료
```

## 현황

### Draft
- [[stocks-master-seed]] — 종목 마스터 시드/동기화 (stocks): 코스피200+코스닥150 적재 ([[disclosure-collection-pipeline]] 선행)

### Approved
- [[disclosure-collection-pipeline]] — DART 공시 수집 파이프라인 (Stage 1): 11개 작업카드/3 wave, DB변경 없음

### Done
*(없음)*

## 작업 흐름

```
/dc-plan <의도>        → Draft/<slug>.md 생성
/dc-tech-review <spec> → 작업 카드 분해, Draft→Approved 검토
/dc-spec-move <slug>   → 상태 전환 (frontmatter + git mv)
```
