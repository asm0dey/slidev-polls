import { describe, expect, it } from "vitest";
import { router } from "./index";

// Regression for BUG-005 — vue-router previously failed at app boot when the
// slug route used a regex with nested capture groups (`[a-z0-9]+(-[a-z0-9]+)*`).
// The path parser emitted a malformed regex ("Unterminated group") that threw
// during module evaluation, so the voter SPA rendered an empty shell for every
// /{slug} URL. These tests boot the real router and drive it through the slug
// paths the backoffice links to, so the regression surfaces at the router
// layer — the component tests in PollView.test.ts bypass the router and would
// not have caught the parse failure.

describe("voter router", () => {
  // @BUG-005 — the slug surfaced in the backoffice for the BUG-004 regression
  // poll (`bug-004-regression-e2e`) must resolve to the poll view without the
  // path parser throwing.
  it("resolves a multi-hyphen slug to the poll view", async () => {
    await router.push("/bug-004-regression-e2e");
    await router.isReady();

    const current = router.currentRoute.value;
    expect(current.name).toBe("poll");
    expect(current.params.slug).toBe("bug-004-regression-e2e");
  });

  it("resolves the bare root to the landing page", async () => {
    await router.push("/");
    await router.isReady();

    expect(router.currentRoute.value.name).toBe("landing");
  });

  it("does not match a path shorter than the backend slug floor", async () => {
    await router.push("/ab");
    await router.isReady();

    // vue-router's path matcher is case-insensitive, which is why uppercase
    // is not a useful negative case here; the {3,40} length bound is what
    // keeps `/ab` (and any other too-short path) from resolving to the poll
    // view. The server's forwarding regex enforces the same floor.
    expect(router.currentRoute.value.matched).toHaveLength(0);
  });
});
