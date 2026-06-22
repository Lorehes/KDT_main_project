---
type: issue
status: open
severity: low
created: 2026-06-22
updated: 2026-06-22
source: dc-review-code (pricing-nav-auth-consistency)
---

# PublicNavbar 비로그인 CTA aria-label 미적용

> 상태: **open** (접근성 개선 후속 과제, WCAG AA 현행 충족)
> 발견: `pricing-nav-auth-consistency` 코드 리뷰 Low 이슈
> 관련: [[pricing-nav-auth-consistency]], [[design_structure]] §접근성

## 배경 / 원인

`PublicNavbar.tsx`의 비로그인 CTA 링크("로그인", "무료로 시작")에 명시적 `aria-label`이 없다.

```tsx
// frontend/src/components/layout/PublicNavbar.tsx:46~51 (현행)
<Link href="/login" className={buttonVariants({ variant: "outline", size: "sm" })}>
  로그인
</Link>
<Link href="/signup" className={buttonVariants({ size: "sm" })}>
  무료로 시작
</Link>
```

반면 로그인 사용자용 "대시보드로" 링크에는 `aria-label="대시보드로 이동"`이 적용되어 있어 **일관성 부재**.

## 현재 규정 준수 상태

| 기준 | 상태 |
|------|------|
| WCAG 2.1 AA SC 2.4.4(링크 목적) | ✅ 충족 — 텍스트 컨텐츠로 목적 이해 가능 |
| WCAG 2.1 AA SC 4.1.2(이름·역할·값) | ✅ 충족 — `<a>` 태그가 접근 가능한 이름 제공 |
| CLAUDE.md §6-5 최소 요구 | ✅ 충족 |

현행으로도 WCAG 2.1 AA를 위반하지 않는다.

## aria-label 추가가 의미 있는 케이스

1. **다국어(i18n) 지원 시**: 텍스트는 번역되더라도 aria-label을 독립 관리할 수 있어 맥락 설명 풍부화 가능.
2. **nav 내 동일 링크 중복 배치 시**: 동일 페이지에 "로그인" 링크가 여러 곳에 배치될 경우 각 링크의 컨텍스트를 구분하는 데 유용.
3. **스크린리더 UX 고도화**: "로그인 — 회원이 아니라면 무료로 시작하세요" 같은 부연 설명 가능.

## 권장 수정

```tsx
// frontend/src/components/layout/PublicNavbar.tsx — 비로그인 분기
<Link
  href="/login"
  className={buttonVariants({ variant: "outline", size: "sm" })}
  aria-label="로그인 페이지로 이동"
>
  로그인
</Link>
<Link
  href="/signup"
  className={buttonVariants({ size: "sm" })}
  aria-label="무료로 시작하기 — 회원가입 페이지로 이동"
>
  무료로 시작
</Link>
```

변경 규모: **2줄 prop 추가**. 런타임 영향 없음. TypeScript 변경 불필요.

## 우선순위 / 행동 기준

**현재 단계에서 즉시 수정 불필요.** 아래 중 하나 해당 시 처리:

- i18n/다국어 지원 Spec 착수 시 함께 처리
- WCAG AAA 레벨 접근성 감사 시 일괄 처리
- QA에서 스크린리더 테스트 시 불편 보고 시

## 관련 파일

- `frontend/src/components/layout/PublicNavbar.tsx:44~53` — 비로그인 CTA 렌더 영역
