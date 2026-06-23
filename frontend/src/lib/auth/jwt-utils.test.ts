import { describe, it, expect } from "vitest";
import { decodeJwtPayload } from "./jwt-utils";

// ── 테스트용 JWT 생성 헬퍼 ────────────────────────────────────────────────────
// 서명 없는 fake JWT (payload만 유효한 base64url JSON)
function makeToken(payload: object, corrupt?: "header" | "payload" | "no-sig"): string {
  const encode = (obj: object) =>
    btoa(JSON.stringify(obj)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  const header = encode({ alg: "HS256", typ: "JWT" });
  const body   = encode(payload);
  if (corrupt === "header")  return `.${body}.fake`;
  if (corrupt === "payload") return `${header}..fake`;
  if (corrupt === "no-sig")  return `${header}.${body}`;
  return `${header}.${body}.fake-sig`;
}

// ── decodeJwtPayload 단위 테스트 ──────────────────────────────────────────────
describe("decodeJwtPayload", () => {
  it("onboarding_completed=true 클레임을 올바르게 파싱한다", () => {
    const token = makeToken({ sub: "42", email: "a@b.com", onboarding_completed: true });
    const claims = decodeJwtPayload(token);
    expect(claims?.onboarding_completed).toBe(true);
    expect(claims?.sub).toBe("42");
  });

  it("onboarding_completed=false 클레임을 올바르게 파싱한다", () => {
    const token = makeToken({ sub: "1", onboarding_completed: false });
    const claims = decodeJwtPayload(token);
    expect(claims?.onboarding_completed).toBe(false);
  });

  it("onboarding_completed 클레임이 없는 구 토큰 — undefined 반환 (backward-compat)", () => {
    const token = makeToken({ sub: "1", email: "old@test.com", tier: "FREE" });
    const claims = decodeJwtPayload(token);
    expect(claims).not.toBeNull();
    expect(claims?.onboarding_completed).toBeUndefined();
  });

  it("payload 파트가 없는 토큰(헤더만) → null 반환", () => {
    expect(decodeJwtPayload("only-one-part")).toBeNull();
  });

  it("payload 파트가 비어 있는 토큰 → null 반환", () => {
    expect(decodeJwtPayload(makeToken({}, "payload"))).toBeNull();
  });

  it("빈 문자열 → null 반환", () => {
    expect(decodeJwtPayload("")).toBeNull();
  });

  it("base64 디코딩 후 JSON이 아닌 경우 → null 반환", () => {
    // payload 파트를 유효한 base64url이지만 JSON이 아닌 값으로 교체
    const notJson = btoa("not-valid-json").replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
    expect(decodeJwtPayload(`header.${notJson}.sig`)).toBeNull();
  });

  it("서명 파트 없는 토큰도 payload 파싱 성공 (서명 검증 안 하므로)", () => {
    const token = makeToken({ onboarding_completed: true }, "no-sig");
    const claims = decodeJwtPayload(token);
    expect(claims?.onboarding_completed).toBe(true);
  });

  it("base64url 특수문자(-, _) 포함 payload를 올바르게 디코딩한다", () => {
    // 큰 숫자 userId로 base64에 +/= 등이 포함될 수 있는 경우 검증
    const token = makeToken({ sub: "9007199254740991", onboarding_completed: true });
    const claims = decodeJwtPayload(token);
    expect(claims?.onboarding_completed).toBe(true);
    expect(claims?.sub).toBe("9007199254740991");
  });
});
