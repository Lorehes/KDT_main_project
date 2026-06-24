/**
 * [목적] share/page.tsx 이미지 캡처·다운로드·공유 Playwright e2e — share-card-capture-playwright-test
 * [이유] captureShareCard()는 실제 Canvas API + oklch CSS 렌더링 필요 → Vitest/happy-dom 불가.
 *   html2canvas-pro가 Tailwind 4 oklch 색상을 올바르게 캡처하는지 실 브라우저(Chromium)로 검증.
 * [사이드 임팩트] 이 spec은 Next.js dev 서버(pnpm dev)가 실행 중이어야 함 (playwright.config.ts webServer 자동 기동).
 *   BE(localhost:8080)는 전량 page.route() 모킹 — 실 BE 불필요.
 * [수정 시 고려사항] test.setTimeout(30_000): html2canvas 캡처 + document.fonts.ready 대기로 기본 20s 초과 가능.
 *   Mock 데이터에 실 매수가·보유 종목 평문 절대 사용 금지(CLAUDE.md §7 — Playwright trace artifact에 포함됨).
 *   navigator.share/canShare 모킹은 addInitScript로 페이지 로드 전 적용해야 신뢰성 확보.
 *
 * 검증 시나리오:
 *  T1 이미지 저장 — 다운로드 이벤트 + 파일명·크기
 *  T2 DOM 배경 색상 — oklch navy 계열 비-블랙 (html2canvas-pro CSS 파싱 대리 지표)
 *  T3 DOM 텍스트 색상 — h2 투명/블랙 아님 (폰트 렌더 대리 지표)
 *  T4 버튼 상태 — 캡처 중 disabled, 완료 후 복원
 *  T5 navigator.share 미지원 — 다운로드 폴백
 *  T6 navigator.share AbortError — 에러 토스트 미발화
 *
 * T2·T3 주의: node-canvas M-chip 빌드 리스크로 PNG 픽셀 직접 샘플링 대신 DOM computed style로 대체.
 *   captureShareCard가 실제로 oklch를 올바르게 캡처하는지는 T1 파일 크기(5KB↑) + T2/T3 DOM 색상으로 간접 검증.
 */

import { test, expect } from "@playwright/test";
import * as fs from "fs/promises";
import { BE_BASE, setAuthCookie, mockMeResponse } from "../helpers/session";

// ─── BE 엔드포인트 ────────────────────────────────────────────────────────────

const BE_ME = `${BE_BASE}/users/me`;
const BE_PORTFOLIOS = `${BE_BASE}/portfolios`;
const BE_DISCLOSURES_GLOB = `${BE_BASE}/disclosures**`;

// ─── Mock 데이터 (공개 종목명만 — 매수가·수량 미포함) ─────────────────────────

const MOCK_PORTFOLIOS = [
  { id: 1, stock_code: "005930", corp_name: "삼성전자" },
  { id: 2, stock_code: "000660", corp_name: "SK하이닉스" },
];

const MOCK_DISCLOSURES = {
  content: [
    {
      id: 101,
      corp_name: "삼성전자",
      report_nm: "임원의 변동",
      sentiment: "POSITIVE",
      is_withheld: false,
      rcept_dt: "20260624",
    },
    {
      id: 102,
      corp_name: "SK하이닉스",
      report_nm: "주요사항보고서",
      sentiment: "NEGATIVE",
      is_withheld: false,
      rcept_dt: "20260624",
    },
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 10,
};

// ─── 테스트 ───────────────────────────────────────────────────────────────────

test.describe("share 카드 이미지 캡처 e2e", () => {
  test.setTimeout(30_000); // html2canvas + document.fonts.ready 대기

  test.beforeEach(async ({ context, page }) => {
    await setAuthCookie(context);
    // AppShell의 기타 BE 호출(알림·포트폴리오 요약 등) catch-all — 먼저 등록 → 낮은 우선순위
    await page.route(`${BE_BASE}/**`, (r) =>
      r.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
    );
    // 특정 mock — 나중에 등록 → 높은 우선순위 (catch-all을 override)
    await page.route(BE_ME, (r) => r.fulfill({ json: mockMeResponse("김테스트") }));
    await page.route(BE_PORTFOLIOS, (r) => r.fulfill({ json: MOCK_PORTFOLIOS }));
    await page.route(BE_DISCLOSURES_GLOB, (r) => r.fulfill({ json: MOCK_DISCLOSURES }));
  });

  // ── T2·T3: DOM 기반 색상·폰트 검증 ──────────────────────────────────────────

  test("(T2·T3) share-card DOM 배경색·텍스트색 비-블랙/비-투명 검증", async ({ page }) => {
    await page.goto("/share");
    await page.waitForSelector("#share-card h2");

    // T2: 카드 배경이 그라디언트로 설정됨 (oklch 파싱 실패 시 none 또는 black 반환)
    const bgImage = await page.evaluate(() => {
      const card = document.getElementById("share-card");
      return card ? window.getComputedStyle(card).backgroundImage : "";
    });
    expect(bgImage).toBeTruthy();
    expect(bgImage).not.toBe("none");

    // T3: h2 텍스트 색상이 투명/검정이 아님 (white text on navy card)
    const textColor = await page.evaluate(() => {
      const h2 = document.querySelector<HTMLElement>("#share-card h2");
      return h2 ? window.getComputedStyle(h2).color : null;
    });
    expect(textColor).toBeTruthy();
    expect(textColor).not.toBe("rgba(0, 0, 0, 0)");
    expect(textColor).not.toBe("rgb(0, 0, 0)");
  });

  // ── T4: 버튼 상태 ─────────────────────────────────────────────────────────────

  test("(T4) 캡처 중 두 버튼 disabled·aria-busy, 완료 후 복원", async ({ page }) => {
    // document.fonts.ready를 2초 지연 → html2canvas 대기 중 disabled 상태 검증 가능.
    // 헤드리스 Chromium에서 캡처가 너무 빠르게 완료되어 transient 상태를 잡지 못하는 문제 대응.
    await page.addInitScript(() => {
      Object.defineProperty(document, "fonts", {
        configurable: true,
        get: () => ({
          ready: new Promise<void>((resolve) => setTimeout(resolve, 2000)),
        }),
      });
    });

    await page.goto("/share");
    await page.waitForSelector("#share-card");

    const dlBtn = page.getByRole("button", { name: "카드 이미지 다운로드" });
    const shareBtn = page.getByRole("button", { name: "카드 이미지 공유" });

    const dlPromise = page.waitForEvent("download");
    await dlBtn.click();

    // fonts.ready 2초 대기 동안 두 버튼 disabled + aria-busy 확인
    await expect(dlBtn).toBeDisabled({ timeout: 1_000 });
    await expect(dlBtn).toHaveAttribute("aria-busy", "true");
    await expect(shareBtn).toBeDisabled({ timeout: 1_000 });

    // 다운로드 완료 대기 (fonts.ready 2초 + html2canvas ~0.2초)
    await dlPromise;

    // 완료 후 복원
    await expect(dlBtn).not.toBeDisabled({ timeout: 5_000 });
    await expect(shareBtn).not.toBeDisabled({ timeout: 5_000 });
  });

  // ── T1: 다운로드 파일명·크기 ─────────────────────────────────────────────────

  test("(T1) 이미지 저장 — 파일명 공시레이더_주간리포트.png + 크기 5KB↑", async ({ page }) => {
    await page.goto("/share");
    await page.waitForSelector("#share-card");

    const [download] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "카드 이미지 다운로드" }).click(),
    ]);

    expect(download.suggestedFilename()).toBe("공시레이더_주간리포트.png");

    const dlPath = await download.path();
    expect(dlPath).not.toBeNull();

    const stat = await fs.stat(dlPath!);
    expect(stat.size).toBeGreaterThan(5_000); // 5KB 미만 = 빈 캡처 신호
  });

  // ── T5: navigator.share 미지원 폴백 ─────────────────────────────────────────

  test("(T5) navigator.share/canShare 미지원 환경 — 공유하기가 다운로드로 폴백", async ({
    page,
  }) => {
    // Web Share API 자체를 제거해 데스크톱/미지원 환경 시뮬레이션
    await page.addInitScript(() => {
      Object.defineProperty(navigator, "share", {
        value: undefined,
        configurable: true,
        writable: true,
      });
      Object.defineProperty(navigator, "canShare", {
        value: undefined,
        configurable: true,
        writable: true,
      });
    });

    await page.goto("/share");
    await page.waitForSelector("#share-card");

    const [download] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "카드 이미지 공유" }).click(),
    ]);

    expect(download.suggestedFilename()).toBe("공시레이더_주간리포트.png");
  });

  // ── T6: AbortError 에러 토스트 미발화 ────────────────────────────────────────

  test("(T6) navigator.share AbortError(사용자 취소) — 에러 토스트 발화 없음", async ({
    page,
  }) => {
    // canShare → true, share → AbortError throw (사용자가 공유 시트 닫는 케이스)
    await page.addInitScript(() => {
      Object.defineProperty(navigator, "canShare", {
        value: () => true,
        configurable: true,
        writable: true,
      });
      Object.defineProperty(navigator, "share", {
        value: async () => {
          throw new DOMException("User cancelled", "AbortError");
        },
        configurable: true,
        writable: true,
      });
    });

    await page.goto("/share");
    await page.waitForSelector("#share-card");

    await page.getByRole("button", { name: "카드 이미지 공유" }).click();

    // 캡처 + AbortError 처리 완료 대기 (버튼 복원으로 확인)
    await expect(page.getByRole("button", { name: "카드 이미지 공유" })).not.toBeDisabled({
      timeout: 20_000,
    });

    // 에러 토스트 미발화 (sonner: [data-sonner-toast][data-type="error"])
    await expect(page.locator('[data-sonner-toast][data-type="error"]')).not.toBeVisible();
  });
});
