// [목적] 온보딩 플로우(가입·로그인·인증 단계) 전용 레이아웃
// [이유] 4단계 스테퍼 가입 플로우는 앱 셸(사이드바·탭바)이 필요 없고, AuthLayout 스플릿 디자인을 공유
// [사이드 임팩트] 이 그룹 하위 모든 라우트에 AuthLayout이 적용됨
// [수정 시 고려사항] 소셜 OAuth 콜백 라우트도 이 그룹에 배치. 스플릿 레이아웃은 md 이상에서만 활성화

export default function AuthGroupLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
