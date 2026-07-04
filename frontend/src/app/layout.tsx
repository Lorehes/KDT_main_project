// [목적] 앱 전체 루트 레이아웃 — 폰트, Provider, 공통 메타데이터 설정
// [이유] Pretendard(한국어 최적화 가변 폰트) + IBM Plex Mono(숫자 단가·코드) 조합이 디자인 토큰 기준
// [사이드 임팩트] 폰트 CSS 변수가 globals.css @theme inline의 --font-sans/--font-mono를 덮어쓴다
// [수정 시 고려사항] next/font/local 경로는 node_modules 심링크 기반 — 빌드 후 .next/static/media에 복사됨. 폰트 추가 시 이 파일에만 등록

import type { Metadata } from "next";
import localFont from "next/font/local";
import { IBM_Plex_Mono } from "next/font/google";
import "./globals.css";
import { Providers } from "./providers";

const pretendard = localFont({
  src: "../../node_modules/pretendard/dist/web/variable/woff2/PretendardVariable.woff2",
  variable: "--font-pretendard",
  display: "swap",
  weight: "100 900",
});

const ibmPlexMono = IBM_Plex_Mono({
  variable: "--font-ibm-plex-mono",
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  display: "swap",
});

export const metadata: Metadata = {
  title: "공시레이더 — DART 공시 AI 해석 알림",
  description:
    "보유 종목의 DART 공시를 AI가 호재·악재로 해석해 카카오 알림톡과 웹 대시보드로 알려드립니다. 본 서비스는 정보 제공 도구이며 투자 자문이 아닙니다.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko" className={`${pretendard.variable} ${ibmPlexMono.variable}`}>
      <body className="antialiased font-sans">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
