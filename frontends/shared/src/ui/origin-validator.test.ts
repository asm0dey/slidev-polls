import { describe, it, expect } from "vitest";
import { validateOrigin } from "./origin-validator";

describe("validateOrigin", () => {
  it.each(["https://example.com", "http://localhost:3030", "https://slides.example.com:8443", "*"])(
    "accepts %s",
    (input) => {
      expect(validateOrigin(input)).toEqual({ ok: true, normalized: input });
    }
  );

  it("strips trailing slash", () => {
    expect(validateOrigin("https://example.com/")).toEqual({
      ok: true,
      normalized: "https://example.com"
    });
  });

  it("strips path", () => {
    expect(validateOrigin("https://example.com/foo/bar")).toEqual({
      ok: true,
      normalized: "https://example.com"
    });
  });

  it.each([
    ["example.com", "needs a scheme"],
    ["://example.com", "needs a scheme"],
    ["ftp://example.com", "scheme must be http or https"],
    ["https://", "needs a host"],
    ["", "is empty"]
  ])("rejects %s", (input, hint) => {
    const r = validateOrigin(input);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.message.toLowerCase()).toContain(hint);
  });
});
