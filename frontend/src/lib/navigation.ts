// [목적] 앱·퍼블릭 네비게이션 항목 중앙 정의 — TopBar·HamburgerDrawer·BottomTabBar·PublicNavbar 파편화 방지
// [이유] NAV_ITEMS 배열이 TopBar·HamburgerDrawer·BottomTabBar·PublicMobileMenu에 각각 로컬 선언되어
//   항목 추가/수정 시 모든 파일을 개별로 동기화해야 하는 유지보수 부채.
// [사이드 임팩트] 모든 nav 컴포넌트가 이 모듈에 의존. 항목 변경 시 자동으로 모든 곳에 반영됨.
//   BottomTabBar는 labelShort 필드 사용(공간 제약). /notifications 탭은 BottomTabBar 전용이라 APP_NAV_ITEMS 미포함.
// [수정 시 고려사항] APP_NAV_ITEMS 변경 시 BottomTabBar의 /notifications 탭은 별도 처리됨을 인지.
//   as const 로 타입 추론 정확도 유지.

import { LayoutDashboard, FileText, Briefcase } from "lucide-react";

export const APP_NAV_ITEMS = [
  { href: "/dashboard",   label: "대시보드",     labelShort: "대시보드", icon: LayoutDashboard },
  { href: "/disclosures", label: "공시 피드",    labelShort: "공시",    icon: FileText },
  { href: "/portfolios",  label: "내 포트폴리오", labelShort: "종목",    icon: Briefcase },
] as const;

export const PUBLIC_NAV_ITEMS = [
  { href: "/#features", label: "기능" },
  { href: "/pricing",   label: "요금제" },
  { href: "/#cases",    label: "고객사례" },
  { href: "/#help",     label: "도움말" },
] as const;
