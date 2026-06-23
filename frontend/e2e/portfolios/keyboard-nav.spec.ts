/**
 * Playwright 통합 테스트 — portfolio-search-keyboard-nav
 *
 * (a) ArrowDown + Enter → /portfolios/add 라우팅 검증 (키보드 종목 선택 메인 플로우)
 * (b) ArrowDown 순환 — 마지막 옵션 → 첫 번째 옵션 wrap-around
 * (c) Escape → 드롭다운 닫힘, activeIndex 리셋
 *
 * 전제 조건:
 *  - Next.js dev 서버가 localhost:3000에서 실행 중이어야 함 (playwright.config.ts webServer로 자동 기동)
 *  - BE(localhost:8080)는 page.route()로 전량 모킹 — 실 BE 불필요
 */

import { test, expect, type BrowserContext } from "@playwright/test";

const BE_PORTFOLIOS = "http://localhost:8080/api/v1/portfolios";
const BE_STOCKS_SEARCH = "http://localhost:8080/api/v1/stocks/search**";

const MOCK_STOCKS = [
  { stock_code: "005930", corp_name: "삼성전자", market: "KOSPI" },
  { stock_code: "000660", corp_name: "SK하이닉스", market: "KOSPI" },
];

// JWT payload를 base64url 인코딩해 dr_session 쿠키용 토큰 생성.
// 미들웨어는 서명 없이 payload만 디코딩(라우팅 목적) — fake signature로 충분.
function makeFakeSessionJwt(): string {
  const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(
    JSON.stringify({
      sub: "1",
      email: "test@example.com",
      role: "FREE",
      onboarding_completed: true,
      exp: 9_999_999_999,
    }),
  ).toString("base64url");
  return `${header}.${payload}.fake_sig`;
}

async function setAuthCookie(context: BrowserContext) {
  await context.addCookies([
    {
      name: "dr_session",
      value: makeFakeSessionJwt(),
      domain: "localhost",
      path: "/",
    },
  ]);
}

test.describe("portfolios/new — 종목 검색 키보드 네비게이션", () => {
  test.beforeEach(async ({ context, page }) => {
    await setAuthCookie(context);
    await page.route(BE_PORTFOLIOS, (r) => r.fulfill({ json: [] }));
    await page.route(BE_STOCKS_SEARCH, (r) => r.fulfill({ json: MOCK_STOCKS }));
  });

  test("(a) ArrowDown + Enter → /portfolios/add 라우팅 + 종목 코드 포함", async ({ page }) => {
    await page.goto("/portfolios/new");

    const input = page.getByRole("combobox", { name: "종목 검색" });
    await input.fill("삼성");

    await expect(page.getByRole("listbox")).toBeVisible();

    // ArrowDown → 첫 번째 옵션 active
    await input.press("ArrowDown");
    await expect(page.getByRole("option").first()).toHaveAttribute("aria-selected", "true");

    // Enter → /portfolios/add?code=005930&... 라우팅
    const navPromise = page.waitForURL(/\/portfolios\/add/);
    await input.press("Enter");
    await navPromise;

    expect(page.url()).toContain("code=005930");
    expect(page.url()).toContain("name=");
  });

  test("(b) ArrowDown 순환 — 마지막 → 첫 번째", async ({ page }) => {
    await page.goto("/portfolios/new");

    const input = page.getByRole("combobox", { name: "종목 검색" });
    await input.fill("삼성");
    await expect(page.getByRole("listbox")).toBeVisible();

    const options = page.getByRole("option");

    // index 0 → 1 → wrap to 0 (MOCK_STOCKS 2건)
    await input.press("ArrowDown");
    await expect(options.nth(0)).toHaveAttribute("aria-selected", "true");
    await input.press("ArrowDown");
    await expect(options.nth(1)).toHaveAttribute("aria-selected", "true");
    await input.press("ArrowDown");
    await expect(options.nth(0)).toHaveAttribute("aria-selected", "true");
  });

  test("(c) Escape → 드롭다운 닫힘", async ({ page }) => {
    await page.goto("/portfolios/new");

    const input = page.getByRole("combobox", { name: "종목 검색" });
    await input.fill("삼성");
    await expect(page.getByRole("listbox")).toBeVisible();

    await input.press("Escape");
    await expect(page.getByRole("listbox")).not.toBeVisible();
  });
});
