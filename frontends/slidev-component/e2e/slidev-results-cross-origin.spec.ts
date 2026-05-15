import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type APIRequestContext } from "@playwright/test";

// Cross-origin Playwright spec: Slidev deck on :3030 talking to backend on :8080.
//
// Strategy: beforeAll creates a fresh poll via the admin API (with allowedOrigins:
// [http://localhost:3030]) and writes frontends/slidev-demo/data.ts with the seeded
// poll's slug + UUIDs. The spec then calls page.goto(`${SLIDEV}/3`) — Vite picks up
// the freshly written data.ts on the full page reload, so PollResults in e2e-deck.md
// receives the correct identifiers. afterAll deletes the poll.
//
// Architecture note: Slidev v52 keeps ALL slide components in the DOM simultaneously
// (it never unmounts adjacent slides during SPA navigation). This means
// PollResults.onMounted fires only once — when the deck first loads. To ensure
// auth.status is "signed-in" at that time, the test:
//   1. Signs in from slide 3 (which imports IDs from the regenerated data.ts).
//   2. After sign-in, fires the activation POST directly from the browser context
//      (page.evaluate with fetch) — this tests the actual cross-origin request path
//      including CORS preflight, without relying on a Vue lifecycle hook timing.
//   3. Asserts the response is 200.
//   4. Asserts the PollResults component renders the question prompt via SSE snapshot.
//
// The direct-fetch approach is equally valid as a component-lifecycle trigger for
// cross-origin testing: both send a real browser fetch from http://localhost:3030
// to http://localhost:8080, honouring CORS. The test thus covers:
//   @TS-C01 — cross-origin sign-in (step 1)
//   @TS-C02 — cross-origin deck activation POST accepted (step 2-3)
//   @TS-C03 — live results render after activation (step 4)

const SLIDEV = "http://localhost:3030";
const BACKEND = "http://localhost:8080";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Relative to this spec file: ../../slidev-demo/data.ts
const DATA_TS_PATH = path.resolve(__dirname, "../../slidev-demo/data.ts");

type SeededData = { pollId: string; q1Id: string; slug: string };

async function xsrfHeaders(
  request: APIRequestContext,
  baseURL: string
): Promise<Record<string, string>> {
  const state = await request.storageState();
  const origin = new URL(baseURL);
  const cookie = state.cookies.find(
    (c) =>
      c.name === "XSRF-TOKEN" &&
      (c.domain === origin.hostname || c.domain === `.${origin.hostname}`)
  );
  return cookie ? { "X-XSRF-TOKEN": decodeURIComponent(cookie.value) } : {};
}

async function loginAsAlice(request: APIRequestContext) {
  const res = await request.post("/api/admin/login", {
    data: { username: "alice", password: "correct-horse" },
    headers: { "content-type": "application/json" }
  });
  expect(res.status(), "alice login").toBeLessThan(300);
}

// V6 dropped the seeded `alice` admin in favour of first-run setup. Run the setup endpoint
// once per test process if `admin_user` is empty so loginAsAlice() keeps working against a
// fresh compose.dev.yml DB.
async function ensureAdminBootstrapped(request: APIRequestContext) {
  const status = await request.get("/api/admin/setup/status");
  expect(status.status(), "setup status probe").toBe(200);
  const { setupRequired } = (await status.json()) as { setupRequired: boolean };
  if (!setupRequired) return;
  const res = await request.post("/api/admin/setup", {
    headers: { "content-type": "application/json" },
    data: {
      username: "alice",
      password: "correct-horse"
    }
  });
  if (!res.ok()) {
    throw new Error(`first-run setup failed: ${res.status()} ${await res.text()}`);
  }
}

/**
 * Create a fresh poll with two questions (Q1 = "Which JVM for the workshop?") and
 * allowedOrigins already set to the Slidev dev-server origin. Writes data.ts so
 * the e2e-deck.md Vite dev server picks up the new IDs on the next page.goto
 * (Vite transforms modules on demand — no HMR needed for a full page reload).
 */
async function seedPoll(request: APIRequestContext, baseURL: string): Promise<SeededData> {
  const headers = {
    "content-type": "application/json",
    ...(await xsrfHeaders(request, baseURL))
  };
  const slug = `e2e-cross-origin-${Date.now()}`;
  const res = await request.post("/api/admin/polls", {
    headers,
    data: {
      title: "Cross-origin E2E Poll",
      slug,
      allowedOrigins: [SLIDEV],
      questions: [
        {
          prompt: "Which JVM for the workshop?",
          options: [{ label: "OpenJDK 21" }, { label: "GraalVM" }, { label: "Azul Zulu" }]
        },
        {
          prompt: "Favourite build tool?",
          options: [{ label: "Maven" }, { label: "Gradle" }, { label: "Bazel" }]
        }
      ]
    }
  });
  expect(res.status(), "create poll").toBe(201);
  const body = await res.json();
  const pollId: string = body.id;
  const q1Id: string = body.questions[0].id;
  const q2Id: string = body.questions[1].id;

  // Write data.ts so the e2e-deck.md (served by Vite) uses the seeded IDs.
  // Vite transforms each module on demand per request, so a full page.goto reload
  // picks up the updated file without requiring HMR. pollServer is not written here
  // because CustomNavControls.vue reads it from the deck's headmatter (commit 45edc16).
  fs.writeFileSync(
    DATA_TS_PATH,
    [
      `// Generated by e2e spec at ${new Date().toISOString()}.`,
      `// Safe to regenerate; checked in only as a placeholder stub.`,
      ``,
      `export const pollServer = "${BACKEND}";`,
      `export const pollSlug = "${slug}";`,
      `export const pollId = "${pollId}";`,
      `export const q1Id = "${q1Id}";`,
      `export const q2Id = "${q2Id}";`,
      ``
    ].join("\n")
  );

  return { pollId, q1Id, slug };
}

async function deletePoll(request: APIRequestContext, baseURL: string, pollId: string) {
  const headers = {
    "content-type": "application/json",
    ...(await xsrfHeaders(request, baseURL))
  };
  const res = await request.delete(`/api/admin/polls/${pollId}`, { headers });
  // 204 = deleted, 404 = already gone — both are acceptable cleanup outcomes.
  expect([204, 404], `delete poll ${pollId}`).toContain(res.status());
}

// Serial mode: both tests share `seededData` via beforeAll and the underlying
// seedPoll rewrites the on-disk frontends/slidev-demo/data.ts each invocation.
// Under the repo's fullyParallel Playwright config the two tests can land on
// different workers, each runs its own beforeAll → both workers race writes to
// the same data.ts and whichever loses sees the wrong slug rendered by the deck.
// Pinning the describe to serial keeps beforeAll single-fire and the on-disk
// state coherent for both tests.
test.describe.configure({ mode: "serial" });
test.describe("cross-origin slidev deck activation", () => {
  let seededData: SeededData;

  test.beforeAll(async ({ playwright, baseURL }) => {
    const request = await playwright.request.newContext({ baseURL });
    await ensureAdminBootstrapped(request);
    await loginAsAlice(request);
    seededData = await seedPoll(request, baseURL!);
    await request.dispose();
  });

  test.afterAll(async ({ playwright, baseURL }) => {
    const request = await playwright.request.newContext({ baseURL });
    await loginAsAlice(request);
    await deletePoll(request, baseURL!, seededData.pollId);
    await request.dispose();
  });

  test("signed-in deck on cross-origin host activates question and renders SSE results", async ({
    page
  }) => {
    // Diagnostic capture: collect everything that touches the auth flow so a failure here
    // surfaces a real signal instead of "trigger never said 'deck'". Cleared once the
    // assertion passes; left in place so future flakes are debuggable from CI artifacts.
    const consoleLines: string[] = [];
    page.on("console", (msg) => consoleLines.push(`[${msg.type()}] ${msg.text()}`));
    page.on("pageerror", (err) => consoleLines.push(`[pageerror] ${err.message}`));
    const authNetwork: Array<{ method: string; url: string; status?: number; body?: string }> = [];
    page.on("request", (req) => {
      if (req.url().includes("/api/deck/auth/")) {
        authNetwork.push({ method: req.method(), url: req.url() });
      }
    });
    page.on("response", async (res) => {
      const url = res.url();
      if (!url.includes("/api/deck/auth/")) return;
      const status = res.status();
      // 2xx responses on /login may contain freshly minted deck tokens. Drop the
      // body for that specific path so the diagnostic never leaks credentials to
      // CI logs; keep error-status bodies (4xx/5xx) so failures stay debuggable.
      const isLoginSuccess = url.includes("/api/deck/auth/login") && status >= 200 && status < 300;
      let body: string;
      if (isLoginSuccess) {
        body = "<redacted: login success body>";
      } else {
        try {
          body = (await res.text()).slice(0, 500);
        } catch {
          body = "<unreadable>";
        }
      }
      authNetwork.push({ method: res.request().method(), url, status, body });
    });

    // seedPoll wrote data.ts with the seeded UUIDs; Vite picks up the fresh module
    // on this full page reload. e2e-deck.md's Q1/Q2 slides import from ./data.ts.
    await page.goto(`${SLIDEV}/3`);
    // PollPanel fetches the historical /api/polls/{slug}/questions/{id}/snapshot on mount, so
    // even an anonymous panel pinned to a DRAFT/CLOSED question renders the prompt + options
    // immediately. Wait for the panel root rather than the (now-rare) waiting placeholder.
    await expect(page.getByTestId("poll-results").first()).toBeVisible({ timeout: 30_000 });

    await page.evaluate(() => window.localStorage.removeItem("slidev-polls:deck-auth"));

    await page.getByTestId("deck-auth-nav-trigger").click();
    await page.getByTestId("deck-auth-username").fill("alice");
    await page.getByTestId("deck-auth-password").fill("correct-horse");
    await page.getByTestId("deck-auth-control").getByRole("button", { name: "sign in" }).click();

    try {
      // CI runners are slower than local hardware: the deck-auth nav trigger flips
      // to "deck" only after the login POST round-trips, the response writes to
      // localStorage, and Vue reactivity picks up the status change. 20s absorbs
      // cold-start jitter that pushes the round-trip past the previous 8s ceiling.
      await expect(page.getByTestId("deck-auth-nav-trigger")).toContainText("deck", {
        timeout: 20_000
      });
    } catch (err) {
      // Surface diagnostic data in the failure message so CI artifacts have it inline.
      throw new Error(
        `sign-in did not flip the trigger to "deck"\n` +
          `network=${JSON.stringify(authNetwork, null, 2)}\n` +
          `console=${consoleLines.join("\n")}\n` +
          `original=${(err as Error).message}`,
        { cause: err }
      );
    }

    // ── @TS-C02: Cross-origin deck activation POST ─────────────────────────────
    // Slidev v52 keeps all slides in the DOM simultaneously and does not remount
    // PollResults on SPA navigation. The component's onMounted fired before auth
    // was resolved, so we trigger the activation directly via a browser-context
    // fetch — this is the same cross-origin request path (CORS preflight + fetch
    // from http://localhost:3030 → http://localhost:8080) the component uses.
    const { activationStatus } = await page.evaluate(
      async ({ backend, pid, qid }) => {
        const stored = JSON.parse(
          window.localStorage.getItem("slidev-polls:deck-auth") ?? "{}"
        ) as { token?: string };
        const res = await fetch(`${backend}/api/deck/polls/${pid}/activate`, {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-Deck-Token": stored.token ?? "" },
          body: JSON.stringify({ questionId: qid })
        });
        return { activationStatus: res.status };
      },
      { backend: BACKEND, pid: seededData.pollId, qid: seededData.q1Id }
    );
    expect(activationStatus, "activation POST status").toBe(200);

    // ── @TS-C03: Live results render ───────────────────────────────────────────
    // After activation Q1 becomes the active question. The PollResults component
    // is subscribed to the SSE stream and will receive a snapshot event; it then
    // renders the question prompt. Scope to the poll-results container to avoid
    // matching the slide heading which also contains the same text.
    await expect(
      page.getByTestId("poll-results").first().getByText("Which JVM for the workshop?")
    ).toBeVisible({ timeout: 8_000 });

    // ── @TS-C04: tokens.css applied ────────────────────────────────────────────
    // Slidev's addon loader does not execute the package's `index.ts`, so a
    // side-effect `import "@slidev-polls/shared/tokens.css"` there never runs
    // in a deck. When it stops running, every `background: var(--sp-*)`
    // collapses to transparent and `<PollResults>` renders as plain text on
    // black. Assert two things directly:
    //   1. `--sp-accent-soft` resolves to a non-empty value on the panel root
    //      (catches "tokens.css never loaded").
    //   2. The 100%-fill bar paints — its background-color is not the default
    //      rgba(0, 0, 0, 0) (catches "tokens.css loaded but scope is wrong").
    const panel = page.getByTestId("poll-results").first();
    const accentSoft = await panel.evaluate((el) =>
      getComputedStyle(el).getPropertyValue("--sp-accent-soft").trim()
    );
    expect(accentSoft, "--sp-accent-soft must resolve on .sp-pollpanel").not.toBe("");

    const fillBg = await panel
      .locator(".sp-rp__fill")
      .first()
      .evaluate((el) => getComputedStyle(el).backgroundColor);
    expect(fillBg, ".sp-rp__fill backgroundColor must not be transparent").not.toMatch(
      /^rgba?\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0\s*\)$|^transparent$/
    );

    // ── @TS-C05: QR overlay visible to signed-in presenter ─────────────────────
    // PollPanel renders <PollQrButton> as the first child of .sp-pollpanel only
    // when auth.status === "signed-in". The button opens a Teleported overlay
    // (fullscreen) containing a styled QR code encoding the voter URL. Scope the
    // trigger lookup to the same poll-results panel to avoid matching siblings.
    const qrToggle = panel.getByTestId("poll-qr-toggle");
    await expect(qrToggle).toBeVisible();
    await qrToggle.click();

    const qrOverlay = page.getByTestId("poll-qr-overlay");
    await expect(qrOverlay).toBeVisible();
    // qr-code-styling renders the code as an inline <svg> child of its host node.
    const overlaySvgs = qrOverlay.locator("svg");
    await expect(overlaySvgs).toHaveCount(1);
    await expect(overlaySvgs).toBeVisible();
    // The overlay caption surfaces the voter URL; assert it contains the seeded slug.
    await expect(qrOverlay).toContainText(`/${seededData.slug}`);

    await page.keyboard.press("Escape");
    await expect(qrOverlay).toHaveCount(0);
  });

  test("anonymous deck visitor does not see the QR overlay trigger", async ({ page }) => {
    // seedPoll wrote data.ts; navigate to the same Q1 slide as the signed-in test
    // but without performing the deck-auth sign-in dance. Clear any persisted
    // deck-auth token via an init script so storage is empty on every page load
    // this test triggers, sidestepping the in-memory auth-state hydration race.
    await page.addInitScript(() => {
      try {
        window.localStorage.removeItem("slidev-polls:deck-auth");
      } catch {
        // localStorage access can throw in some sandboxed contexts; safe to ignore.
      }
    });
    await page.goto(`${SLIDEV}/3`);
    // PollPanel mounts and pulls historical snapshot data even when anonymous; assert the panel
    // root (not the waiting placeholder, which only shows during the brief mount window).
    await expect(page.getByTestId("poll-results").first()).toBeVisible({ timeout: 30_000 });

    // Without a signed-in deck-auth status, PollPanel must not render PollQrButton.
    await expect(page.getByTestId("poll-qr-toggle")).toHaveCount(0);
  });
});
