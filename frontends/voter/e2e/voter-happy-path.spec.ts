import { expect, test, type APIRequestContext } from "@playwright/test";

// End-to-end smoke for the anonymous voter path. Seeds a poll via the admin HTTP surface so the
// test is self-contained against any running backend (no DB poking, no SQL fixtures): login as
// alice, create a poll with a randomised slug, activate its question, run the voter flow in a
// real browser, then delete the poll on teardown.
//
// The test is gated on a running backend listening at PW_BASE_URL (defaults to
// http://localhost:8080). `task test:e2e:voter` auto-provisions that backend via compose.dev.yml
// when :8080 is idle and tears it down on exit.

type Fixture = {
  slug: string;
  pollId: string;
  questionId: string;
  firstOptionLabel: string;
};

// The admin surface is CSRF-protected (`CookieCsrfTokenRepository.withHttpOnlyFalse()` over
// `/api/admin/**` minus `/login`). Spring writes the `XSRF-TOKEN` cookie on every response; the
// client has to echo it back as `X-XSRF-TOKEN` on state-changing calls (see BUG-004). The SPA's
// `AdminApiClient` does this; the raw Playwright request context does not, so helpers here pull
// the cookie out of the context and attach the header by hand.
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

// V6 dropped the seeded `alice` admin in favour of first-run setup. Run the setup endpoint once
// per test process if `admin_user` is empty so the existing loginAsAlice() helper keeps working.
async function ensureAdminBootstrapped(request: APIRequestContext) {
  const status = await request.get("/api/admin/setup/status");
  expect(status.status(), "setup status probe").toBe(200);
  const { setupRequired } = (await status.json()) as { setupRequired: boolean };
  if (!setupRequired) return;
  const res = await request.post("/api/admin/setup", {
    headers: { "content-type": "application/json" },
    data: {
      username: "alice",
      password: "correct-horse",
      displayName: "Alice Presenter"
    }
  });
  if (!res.ok()) {
    throw new Error(`first-run setup failed: ${res.status()} ${await res.text()}`);
  }
}

async function seedPoll(request: APIRequestContext, baseURL: string): Promise<Fixture> {
  // A unique slug per run avoids collisions across re-runs against the same DB.
  const slug = `e2e-voter-${Date.now().toString(36)}`;
  const csrf = await xsrfHeaders(request, baseURL);
  const create = await request.post("/api/admin/polls", {
    headers: { "content-type": "application/json", ...csrf },
    data: {
      title: `E2E voter ${slug}`,
      slug,
      questions: [
        {
          prompt: "Which JVM?",
          options: [{ label: "OpenJDK" }, { label: "GraalVM" }]
        }
      ]
    }
  });
  expect(create.status(), "create poll").toBe(201);
  const body = (await create.json()) as {
    id: string;
    slug: string;
    questions: Array<{ id: string; options: Array<{ id: string; label: string }> }>;
  };
  const questionId = body.questions[0].id;
  const firstOptionLabel = body.questions[0].options[0].label;

  const open = await request.post(`/api/admin/polls/${body.id}/open`, {
    headers: { "content-type": "application/json", ...(await xsrfHeaders(request, baseURL)) },
    data: { questionId }
  });
  expect(open.status(), "activate question").toBe(200);

  return { slug: body.slug, pollId: body.id, questionId, firstOptionLabel };
}

async function deletePoll(request: APIRequestContext, baseURL: string, pollId: string) {
  await request.delete(`/api/admin/polls/${pollId}`, {
    headers: await xsrfHeaders(request, baseURL)
  });
}

test.describe("voter happy path", () => {
  let fixture: Fixture;

  test.beforeAll(async ({ playwright, baseURL }) => {
    const request = await playwright.request.newContext({ baseURL });
    await ensureAdminBootstrapped(request);
    await loginAsAlice(request);
    fixture = await seedPoll(request, baseURL!);
    await request.dispose();
  });

  test.afterAll(async ({ playwright, baseURL }) => {
    const request = await playwright.request.newContext({ baseURL });
    await loginAsAlice(request);
    await deletePoll(request, baseURL!, fixture.pollId);
    await request.dispose();
  });

  test("anonymous visitor votes and sees confirmation", async ({ page }) => {
    await page.goto(`/${fixture.slug}`);

    // Active view renders: prompt + options are visible. Query by role rather than testid so
    // the test still holds if the visual layer is restyled without the hooks being touched.
    await expect(page.getByRole("heading", { level: 2, name: /Which JVM\?/i })).toBeVisible();
    const firstOption = page.getByRole("button", { name: fixture.firstOptionLabel });
    await expect(firstOption).toBeVisible();

    await firstOption.click();

    await expect(page.getByTestId("poll-voted")).toBeVisible();
    await expect(page.getByTestId("poll-voted")).toContainText(/your vote is in/i);
  });
});
