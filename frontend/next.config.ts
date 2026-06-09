// [목적] Next.js 빌드·런타임 설정 — 보안 헤더·CSP·strict mode·X-Powered-By 제거
// [이유] XSS/클릭재킹/MIME sniff 방어를 위해 보안 헤더 명시 필요.
//   CSP connect-src에 API_URL 포함 — FE가 BE API와 통신할 수 있도록 허용하되 출처 제한.
//   'unsafe-inline' for style-src: shadcn/ui가 인라인 스타일 사용. 카카오페이 SDK 도입 시 script-src 확장 필요.
// [사이드 임팩트] reactStrictMode는 개발 모드에서 effect 이중 실행 — useEffect 부수효과 주의.
//   CSP 위반 시 브라우저 콘솔에 오류 — 새 외부 도메인 추가 시 connect-src/img-src 업데이트 필요.
// [수정 시 고려사항] 카카오페이 SDK 도입 시 script-src에 'static.pay.kakao.com' 추가.
//   이미지 도메인(KRX·DART) 추가 시 img-src에 해당 도메인 추가 + images.remotePatterns 설정.
//   nonce 기반 CSP로 전환 시 'unsafe-inline' script-src 제거 가능(보안 강화).

import type { NextConfig } from "next";

const apiOrigin = process.env.NEXT_PUBLIC_API_URL ?? "";

const cspDirectives = [
  "default-src 'self'",
  "script-src 'self'",
  `connect-src 'self'${apiOrigin ? ` ${apiOrigin}` : ""} wss:`,
  "img-src 'self' data: https:",
  "style-src 'self' 'unsafe-inline'",
  "font-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join("; ");

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,

  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
          { key: "X-Frame-Options",            value: "DENY" },
          { key: "X-Content-Type-Options",      value: "nosniff" },
          { key: "Referrer-Policy",             value: "strict-origin-when-cross-origin" },
          { key: "Permissions-Policy",           value: "camera=(), microphone=(), geolocation=()" },
          { key: "Content-Security-Policy",      value: cspDirectives },
        ],
      },
    ];
  },
};

export default nextConfig;
