import {
  expect,
  test,
  type APIRequestContext,
  type BrowserContext,
  type Page
} from "@playwright/test";

// End-to-end product flow: admin registers (or logs in if already provisioned), creates a poll
// in the backoffice UI, opens the voter SPA in a second browser context, activates the first
// question from the presenter tab, and asserts the voter tab transitions from `poll-waiting`
// to `poll-active` via the SSE stream.
//
// Two distinct browser contexts keep cookies isolated: the presenter holds an authenticated
// session, the voter is anonymous. Both share the same backend baseURL because the fat-JAR
// serves the backoffice SPA at /admin/ and the voter SPA at /:slug.

const ADMIN_USERNAME = "alice";
const ADMIN_PASSWORD = "correct-horse";
const ADMIN_DISPLAY = "Alice Presenter";

type SeededPoll = {
  slug: string;
  title: string;
  questionPrompt: string;
  firstOptionLabel: string;
};

async function isSetupRequired(request: APIRequestContext): Promise<boolean> {
  const res = await request.get("/api/admin/setup/status");
  expect(res.status(), "setup status probe").toBe(200);
  const body = (await res.json()) as { setupRequired: boolean };
  return body.setupRequired;
}

async function registerAdmin(page: Page) {
  await page.goto("/admin/setup");
  await expect(page.getByTestId("setup-page")).toBeVisible();
  await page.getByTestId("setup-username").fill(ADMIN_USERNAME);
  await page.getByTestId("setup-displayname").fill(ADMIN_DISPLAY);
  await page.getByTestId("setup-password").fill(ADMIN_PASSWORD);
  await page.getByTestId("setup-submit").click();
  // SetupPage navigates to /polls on success.
  await expect(page.getByTestId("poll-list-page")).toBeVisible({ timeout: 10_000 });
}

async function loginAdmin(page: Page) {
  await page.goto("/admin/login");
  await expect(page.getByTestId("login-page")).toBeVisible();
  await page.getByTestId("login-username").fill(ADMIN_USERNAME);
  await page.getByTestId("login-password").fill(ADMIN_PASSWORD);
  await page.getByTestId("login-submit").click();
  await expect(page.getByTestId("poll-list-page")).toBeVisible({ timeout: 10_000 });
}

async function ensurePresenterSignedIn(
  page: Page,
  request: APIRequestContext
): Promise<"registered" | "logged-in"> {
  if (await isSetupRequired(request)) {
    try {
      await registerAdmin(page);
      return "registered";
    } catch (err) {
      // A parallel spec may have raced us to /api/admin/setup. Fall through to login.
      if (await isSetupRequired(request)) throw err;
    }
  }
  await loginAdmin(page);
  return "logged-in";
}

async function createPollViaUi(page: Page): Promise<SeededPoll> {
  const stamp = Date.now().toString(36);
  const seed: SeededPoll = {
    slug: `e2e-fullflow-${stamp}`,
    title: `E2E full flow ${stamp}`,
    questionPrompt: "Which JVM ships with the workshop image?",
    firstOptionLabel: "OpenJDK 21"
  };

  await page.getByTestId("new-poll-link").click();
  await expect(page.getByTestId("poll-editor-page")).toBeVisible();

  await page.getByTestId("poll-title").fill(seed.title);
  // SlugField exposes its visible input as data-testid="slug-input"
  // (SlugField.vue:56). The PollEditor page also keeps a hidden poll-slug input as a legacy
  // mirror — fill the SlugField input directly so its availability check sees the value.
  await page.getByTestId("slug-input").fill(seed.slug);

  await page.getByTestId("question-prompt").fill(seed.questionPrompt);
  const optionInputs = page.getByTestId("option-label");
  await optionInputs.nth(0).fill(seed.firstOptionLabel);
  await optionInputs.nth(1).fill("GraalVM 21");

  await page.getByTestId("poll-editor-submit").click();
  // Save returns to the poll list.
  await expect(page.getByTestId("poll-list-page")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId("poll-row").filter({ hasText: seed.title })).toBeVisible();
  return seed;
}

async function openPollEditorFor(page: Page, title: string) {
  // PollListPage renders a poll-edit link per row (PollListPage.vue:144). Click the row whose
  // title matches the seeded one to land on the editor for the freshly created poll.
  const row = page.getByTestId("poll-row").filter({ hasText: title });
  await row.getByTestId("poll-edit").click();
  await expect(page.getByTestId("poll-editor-page")).toBeVisible();
}

test.describe.serial("admin creates poll, voter sees activation live", () => {
  let presenterContext: BrowserContext;
  let voterContext: BrowserContext;
  let presenterPage: Page;
  let voterPage: Page;
  let seed: SeededPoll;

  test.beforeAll(async ({ browser }) => {
    presenterContext = await browser.newContext();
    voterContext = await browser.newContext();
    presenterPage = await presenterContext.newPage();
    voterPage = await voterContext.newPage();
  });

  test.afterAll(async () => {
    await presenterContext.close();
    await voterContext.close();
  });

  test("registers admin (or logs in), creates a poll, voter flips to active on activation", async ({
    playwright,
    baseURL
  }) => {
    const apiRequest = await playwright.request.newContext({ baseURL });

    // ─── 1. presenter registers or logs in ───────────────────────────────────
    await ensurePresenterSignedIn(presenterPage, apiRequest);

    // ─── 2. presenter creates a poll via the editor UI ───────────────────────
    seed = await createPollViaUi(presenterPage);

    // ─── 3a. voter loads the slug page BEFORE activation — waiting state ─────
    await voterPage.goto(`/${seed.slug}`);
    await expect(voterPage.getByTestId("poll-waiting")).toBeVisible({ timeout: 10_000 });

    // ─── 3b. presenter activates the question from the editor ────────────────
    await openPollEditorFor(presenterPage, seed.title);
    await presenterPage.getByTestId("question-activate").click();
    // The editor reloads detail and the question pill flips to ACTIVE.
    await expect(
      presenterPage.getByTestId("poll-editor-page").getByText(/active/i).first()
    ).toBeVisible({ timeout: 8_000 });

    // ─── 3c. voter receives the SSE snapshot and renders the active question ─
    await expect(voterPage.getByTestId("poll-active")).toBeVisible({ timeout: 10_000 });
    await expect(
      voterPage.getByRole("heading", { level: 2, name: new RegExp(seed.questionPrompt, "i") })
    ).toBeVisible();
    const firstOption = voterPage.getByRole("button", { name: seed.firstOptionLabel });
    await expect(firstOption).toBeVisible();

    // ─── 3d. voter casts a vote via the two-step UX (option select → submit) ─
    await firstOption.click();
    await expect(firstOption).toHaveAttribute("data-selected", "");
    await voterPage.getByTestId("poll-submit").click();
    await expect(voterPage.getByTestId("poll-voted")).toBeVisible({ timeout: 8_000 });
    await expect(voterPage.getByTestId("poll-voted")).toContainText(/answer recorded/i);
    await expect(voterPage.getByTestId("poll-voted")).toContainText(seed.firstOptionLabel);

    await apiRequest.dispose();
  });
});
