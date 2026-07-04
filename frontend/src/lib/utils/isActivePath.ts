// [목적] 현재 경로가 주어진 href에 해당하는 네비게이션 항목의 활성 상태를 판단
// [이유] Sidebar·BottomTabBar 양쪽에 동일한 활성 경로 판단 로직이 인라인으로 중복(R8).
//   dashboard는 exact match만(하위 경로 제외), 나머지는 startsWith 매칭.
// [사이드 임팩트] Sidebar·BottomTabBar 모두 이 함수를 사용하므로 네비게이션 활성 로직이 단일화됨.
// [수정 시 고려사항] exact 옵션으로 호출처가 명시적으로 exact 여부를 선언 가능.
//   "/dashboard" 하드코딩 기본값은 exact:true로 처리됨.

/**
 * pathname이 href 네비게이션 항목의 활성 경로인지 판단.
 * exact: true이거나 href === "/dashboard"면 strict equality, 그 외는 startsWith.
 */
export function isActivePath(pathname: string, href: string, opts?: { exact?: boolean }): boolean {
  const exact = opts?.exact ?? href === "/dashboard";
  if (exact) return pathname === href;
  // pathname.startsWith(href + "/") 로 /portfolios-v2 가 /portfolios 를 활성화하는 오매칭 방지
  return pathname === href || pathname.startsWith(href + "/");
}
