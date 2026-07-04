import { describe, it, expect } from "vitest";
import { isActivePath } from "@/lib/utils/isActivePath";

describe("isActivePath", () => {
  // /dashboard 는 기본값 exact: true
  describe("exact match (default for /dashboard)", () => {
    it("/dashboard 는 /dashboard 와 정확히 일치할 때만 활성", () => {
      expect(isActivePath("/dashboard", "/dashboard")).toBe(true);
    });

    it("/dashboard/reports 는 /dashboard를 활성화하지 않음", () => {
      expect(isActivePath("/dashboard/reports", "/dashboard")).toBe(false);
    });

    it("다른 경로는 /dashboard 를 활성화하지 않음", () => {
      expect(isActivePath("/disclosures", "/dashboard")).toBe(false);
    });
  });

  // exact: true 명시
  describe("exact: true 옵션", () => {
    it("정확히 일치하면 true", () => {
      expect(isActivePath("/portfolios", "/portfolios", { exact: true })).toBe(true);
    });

    it("하위 경로는 false", () => {
      expect(isActivePath("/portfolios/123", "/portfolios", { exact: true })).toBe(false);
    });
  });

  // startsWith 매칭 (exact 미지정 + href !== /dashboard)
  describe("prefix 매칭 (기본: exact: false, href ≠ /dashboard)", () => {
    it("정확히 일치하면 true", () => {
      expect(isActivePath("/portfolios", "/portfolios")).toBe(true);
    });

    it("/portfolios/123 은 /portfolios 를 활성화", () => {
      expect(isActivePath("/portfolios/123", "/portfolios")).toBe(true);
    });

    it("/disclosures/abc 는 /disclosures 를 활성화", () => {
      expect(isActivePath("/disclosures/abc", "/disclosures")).toBe(true);
    });

    // 버그 수정 검증: startsWith(href) 방식이면 /portfolios-v2 가 /portfolios 를 활성화함
    it("/portfolios-v2 는 /portfolios 를 활성화하지 않음 (오매칭 방지)", () => {
      expect(isActivePath("/portfolios-v2", "/portfolios")).toBe(false);
    });

    it("/disclosures-old 는 /disclosures 를 활성화하지 않음", () => {
      expect(isActivePath("/disclosures-old", "/disclosures")).toBe(false);
    });

    it("루트 경로 /items-extra 는 /items 를 활성화하지 않음", () => {
      expect(isActivePath("/items-extra", "/items")).toBe(false);
    });

    it("완전히 다른 경로는 false", () => {
      expect(isActivePath("/settings", "/portfolios")).toBe(false);
    });
  });

  describe("경계값", () => {
    it("루트(/) 는 자기 자신과 일치", () => {
      expect(isActivePath("/", "/")).toBe(true);
    });

    it("빈 pathname 은 어떤 href 와도 일치하지 않음", () => {
      expect(isActivePath("", "/portfolios")).toBe(false);
    });
  });
});
