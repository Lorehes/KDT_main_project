// [목적] R10 Playwright 통합 테스트 설정 — fe-auth-token-refresh-flow-rewrite
// [이유] 인증 플로우(Promise 큐 / refresh 실패 redirect / BroadcastChannel)는 브라우저 환경 필수
// [수정 시 고려사항] CI 환경에서는 pnpm build && pnpm start 로 교체 권장

import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 20_000,
  expect: { timeout: 5_000 },
  fullyParallel: false, // 인증 상태 공유 방지 — 순차 실행
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [["html", { open: "never" }]],
  use: {
    baseURL: "http://localhost:3000",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
  ],
  webServer: {
    command: "pnpm dev",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
