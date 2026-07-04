/**
 * R10 Playwright 통합 테스트 — fe-auth-token-refresh-flow-rewrite
 *
 * (a) 동시 401 5건 → /api/auth/refresh 1회만 호출됨 (Promise 큐 검증)
 * (b) refresh 실패 → /login redirect (fallback 경로 검증)
 * (c) 탭 A 로그아웃 → 탭 B BroadcastChannel로 /login 이동 (다중 탭 동기화 검증)
 *
 * 전제 조건:
 *  - Next.js dev 서버가 localhost:3000에서 실행 중이어야 함 (playwright.config.ts webServer로 자동 기동)
 *  - BE(localhost:8080)는 page.route()로 전량 모킹 — 실 BE 불필요
 */

import { test, expect, type Page, type BrowserContext } from "@playwright/test";

// ─── 상수 ─────────────────────────────────────────────────────────────────────

/** Next.js dev 환경 BASE_URL 기본값 (NEXT_PUBLIC_API_URL 미설정 시) */
const BE_ME = "http://localhost:8080/api/v1/users/me";

/** client.ts performRefresh()가 호출하는 Route Handler */
const REFRESH_URL = "http://localhost:3000/api/auth/refresh";

/** refresh 실패 후 client.ts가 호출하는 쿠키 클리어 Route Handler */
const LOGOUT_URL = "http://localhost:3000/api/auth/logout";

/** 픽스처 페이지 경로 */
const TEST_PAGE_CONCURRENT = "/test/concurrent-auth?mode=concurrent";
const TEST_PAGE_LISTENER = "/test/concurrent-auth";

/** 미들웨어가 인식하는 세션 쿠키 (값은 임의, 존재 여부만 확인) */
const SESSION_COOKIE = { name: "dr_session", value: "mock-test-token", url: "http://localhost:3000" };

const ME_BODY = JSON.stringify({ id: 1, email: "t@t.com", nickname: "t", tier: "FREE", tier_expires_at: null });
const ERR_401 = JSON.stringify({ status: 401, code: "UNAUTHENTICATED", message: "인증이 필요합니다.", path: "/api/v1/users/me" });

// ─── 헬퍼 ─────────────────────────────────────────────────────────────────────

async function grantSession(ctx: BrowserContext) {
  await ctx.addCookies([SESSION_COOKIE]);
}

// ─── 테스트 ───────────────────────────────────────────────────────────────────

test.describe("(a) 동시 401 → refresh 1회 보장 (Promise 큐)", () => {
  test("5개 병렬 요청이 401일 때 refresh는 정확히 1번만 호출되고 모든 요청이 성공한다", async ({ page }) => {
    await grantSession(page.context());

    let meCallCount = 0;
    let refreshCallCount = 0;

    // BE /users/me: 처음 5건 → 401, 이후 재시도 → 200
    await page.route(BE_ME, (route) => {
      meCallCount++;
      route.fulfill(
        meCallCount <= 5
          ? { status: 401, contentType: "application/json", body: ERR_401 }
          : { status: 200, contentType: "application/json", body: ME_BODY },
      );
    });

    // Route Handler /api/auth/refresh → 성공
    await page.route(REFRESH_URL, (route) => {
      refreshCallCount++;
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ ok: true }) });
    });

    await page.goto(TEST_PAGE_CONCURRENT);

    // 5개 요청이 모두 완료될 때까지 대기 (status가 pending에서 변경)
    await page.waitForFunction(
      () => document.querySelector("[data-testid='status']")?.textContent !== "pending",
      { timeout: 10_000 },
    );

    const status = await page.getByTestId("status").textContent();

    // 5건 모두 최종 200으로 성공
    expect(status).toBe("done:5:0");

    // Promise 큐: refresh는 1회만 발생
    expect(refreshCallCount).toBe(1);

    // BE는 최초 5회(401) + 재시도 5회(200) = 10회 호출
    expect(meCallCount).toBe(10);
  });
});

test.describe("(b) refresh 실패 → /login redirect", () => {
  test("refresh가 401을 반환하면 /login으로 리다이렉트된다", async ({ page }) => {
    await grantSession(page.context());

    // BE /users/me → 항상 401
    await page.route(BE_ME, (route) =>
      route.fulfill({ status: 401, contentType: "application/json", body: ERR_401 }),
    );

    // Route Handler refresh → 실패
    await page.route(REFRESH_URL, (route) =>
      route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ error: "refresh 실패" }) }),
    );

    // client.ts가 refresh 실패 후 logout을 호출 — 200으로 응답 (쿠키 클리어 흉내)
    await page.route(LOGOUT_URL, (route) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ ok: true }) }),
    );

    await page.goto(TEST_PAGE_CONCURRENT);

    // window.location.href = '/login' 이 실행되면 브라우저가 /login으로 이동
    await page.waitForURL("**/login", { timeout: 10_000 });
    expect(page.url()).toContain("/login");
  });
});

test.describe("(c) 다중 탭 BroadcastChannel 동기화", () => {
  test("탭 A에서 logout 이벤트를 발행하면 탭 B가 /login으로 이동한다", async ({ browser }) => {
    // 두 탭이 BroadcastChannel로 통신하려면 동일 BrowserContext 필요
    const context = await browser.newContext();
    await context.addCookies([SESSION_COOKIE]);

    const pageA = await context.newPage();
    const pageB = await context.newPage();

    // 두 탭 모두 BE 호출 모킹 (mount 시 apiClient 호출을 안정적으로 처리)
    for (const p of [pageA, pageB]) {
      await p.route(BE_ME, (route) =>
        route.fulfill({ status: 200, contentType: "application/json", body: ME_BODY }),
      );
    }

    // 두 탭 모두 테스트 페이지로 이동 — AuthBroadcastListener 마운트됨
    await pageA.goto(TEST_PAGE_LISTENER);
    await pageB.goto(TEST_PAGE_LISTENER);

    // AuthBroadcastListener의 useEffect가 실행(구독 등록)될 때까지 대기
    await pageA.waitForSelector("[data-testid='status']");
    await pageB.waitForSelector("[data-testid='status']");

    // 탭 A에서 BroadcastChannel로 logout 이벤트 직접 발행
    await pageA.evaluate(() => {
      const ch = new BroadcastChannel("dr_auth");
      ch.postMessage({ type: "logout" });
      ch.close();
    });

    // 탭 B의 AuthBroadcastListener가 이벤트를 수신해 router.push('/login') 실행
    await pageB.waitForURL("**/login", { timeout: 5_000 });
    expect(pageB.url()).toContain("/login");

    await context.close();
  });

  test("BroadcastChannel 미지원 환경에서 localStorage 폴백으로도 탭 B가 /login으로 이동한다", async ({ browser }) => {
    const context = await browser.newContext();
    await context.addCookies([SESSION_COOKIE]);

    const pageA = await context.newPage();
    const pageB = await context.newPage();

    for (const p of [pageA, pageB]) {
      await p.route(BE_ME, (route) =>
        route.fulfill({ status: 200, contentType: "application/json", body: ME_BODY }),
      );
    }

    // BroadcastChannel을 비활성화해 localStorage 폴백 경로 강제
    for (const p of [pageA, pageB]) {
      await p.addInitScript(() => {
        // @ts-expect-error — 테스트 목적: BroadcastChannel 비활성화
        delete window.BroadcastChannel;
      });
    }

    await pageA.goto(TEST_PAGE_LISTENER);
    await pageB.goto(TEST_PAGE_LISTENER);

    await pageA.waitForSelector("[data-testid='status']");
    await pageB.waitForSelector("[data-testid='status']");

    // localStorage storage event 폴백 경로: set + 즉시 remove 패턴
    await pageA.evaluate(() => {
      const msg = JSON.stringify({ type: "logout", ts: Date.now() });
      window.localStorage.setItem("dr_auth", msg);
      window.localStorage.removeItem("dr_auth");
    });

    await pageB.waitForURL("**/login", { timeout: 5_000 });
    expect(pageB.url()).toContain("/login");

    await context.close();
  });
});
