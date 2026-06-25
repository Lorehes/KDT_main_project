---
type: issue
status: Closed
created: 2026-06-23
updated: 2026-06-25
resolved: 2026-06-25
source: dc-review-code (dashboard-real-data Wave 1)
priority: P3
---

# 대시보드 `today` 자정 경과 시 갱신 안 됨 (자정 고착 엣지케이스)

> **상태**: Closed — 2026-06-25 해결.
> `src/lib/hooks/useTodaySeoul.ts` 훅 구현 후 `dashboard/page.tsx`에 적용.
> 자정(Asia/Seoul)까지 남은 ms를 setTimeout으로 계산해 today 상태 자동 갱신.

## 현상

`dashboard/page.tsx:27`의 `today` 값은 컴포넌트 렌더마다 `Intl.DateTimeFormat("sv", {timeZone:"Asia/Seoul"}).format(new Date())`로 계산된다.

- **리렌더 발생 시**: 자정 이후 첫 리렌더에 새 날짜로 갱신됨 ✅
- **리렌더 없을 시**: 자정 전 마지막 렌더 시점의 날짜가 고착 → `useDisclosures` 쿼리 키가 어제 날짜로 유지

### 재현 조건

1. 대시보드 탭을 23:55에 열어 두고 화면 인터랙션 없이 대기
2. 00:05 이후 탭 확인 → 여전히 어제 날짜의 공시 표시

## 영향

- Free 사용자: BE가 오늘(Seoul) 강제하므로 실제 데이터는 오늘 기준 ✅ (BE 보호)
- Pro/Premium 사용자: FE `from/to=어제`를 전달 → 어제 공시 반환 (날짜 불일치)
- 빈도: 매우 낮음 (한국 자정 = 0시, 활성 사용자 드묾)

## 왜 `useMemo([], [])` 적용이 해결책이 아닌가

`useMemo(() => getToday(), [])` 는 마운트 1회만 계산 → 리렌더로도 갱신 불가. 오히려 현재 인라인 계산(리렌더마다 fresh)이 더 나음. 타이머 방식이 필요.

## 수정 방향

자정까지 남은 시간(ms)을 계산해 `setTimeout`으로 `today` state를 갱신:

```tsx
function getSeoulToday() {
  return new Intl.DateTimeFormat("sv", { timeZone: "Asia/Seoul" }).format(new Date());
}

function useTodaySeoul() {
  const [today, setToday] = useState(getSeoulToday);
  useEffect(() => {
    // 자정(Seoul)까지 남은 ms 계산
    const now = new Date();
    const seoulNow = new Date(now.toLocaleString("en-US", { timeZone: "Asia/Seoul" }));
    const msUntilMidnight =
      new Date(seoulNow.getFullYear(), seoulNow.getMonth(), seoulNow.getDate() + 1).getTime() -
      seoulNow.getTime();
    const timer = setTimeout(() => setToday(getSeoulToday()), msUntilMidnight + 100);
    return () => clearTimeout(timer);
  }, [today]); // today 바뀌면 다음 자정 타이머 재설정
  return today;
}
```

`useTodaySeoul` 훅을 `lib/hooks/useTodaySeoul.ts`로 분리 후 대시보드에서 사용.

## 다음 단계

- [ ] `frontend/src/lib/hooks/useTodaySeoul.ts` 훅 구현
- [ ] `dashboard/page.tsx` 적용
- [ ] Vitest 단위 테스트 (자정 mock Date)
- [ ] 우선순위 P3 — MVP 이후 후속 (Free는 BE가 보호하므로 실제 피해 거의 없음)
