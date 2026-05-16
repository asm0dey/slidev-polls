import {
  expect,
  test,
  type APIRequestContext,
  type BrowserContext,
  type Page
} from "@playwright/test";

// End-to-end happy paths for per-question multiple choice arity (Task 18):
//   1. multi-choice ballot — three options, min=1/max=3, voter picks two and submits;
//   2. abstain ballot — two options, min=0/max=2, voter skips with zero selections.
//
// Mirrors admin-creates-poll-voter-sees-active.spec.ts: shares the ADMIN credentials
// and the registers-or-logs-in flow, drives the editor UI for poll creation, and
// flips activation through /api/admin/polls/{id}/open with the presenter's CSRF cookie.
//
// Each test owns its own pair of browser contexts to keep cookie isolation: presenter
// stays authenticated, voter stays anonymous.

const ADMIN_USERNAME = "alice";
const ADMIN_PASSWORD = "correct-horse";

type Arity = { min: number; max: number };

type SeededPoll = {
  slug: string;
  title: string;
  questionPrompt: string;
  optionLabels: string[];
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
  await page.getByTestId("setup-password").fill(ADMIN_PASSWORD);
  await page.getByTestId("setup-submit").click();
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

async function ensurePresenterSignedIn(page: Page, request: APIRequestContext) {
  if (await isSetupRequired(request)) {
    try {
      await registerAdmin(page);
      return;
    } catch (err) {
      if (await isSetupRequired(request)) throw err;
    }
  }
  await loginAdmin(page);
}

async function createPollViaUi(
  page: Page,
  prefix: string,
  prompt: string,
  arity: Arity,
  optionLabels: string[]
): Promise<SeededPoll> {
  expect(optionLabels.length, "at least two options").toBeGreaterThanOrEqual(2);
  const stamp = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  const seed: SeededPoll = {
    slug: `${prefix}-${stamp}`,
    title: `${prefix} ${stamp}`,
    questionPrompt: prompt,
    optionLabels
  };

  await page.getByTestId("new-poll-link").click();
  await expect(page.getByTestId("poll-editor-page")).toBeVisible();

  await page.getByTestId("poll-title").fill(seed.title);
  await page.getByTestId("slug-input").fill(seed.slug);
  await page.getByTestId("question-prompt").fill(seed.questionPrompt);

  // Toggle into "Pick many" mode so the arity inputs appear. The editor seeds
  // a sensible default of min=1/max=3 when you flip the radio, but we'll force
  // the explicit values below.
  await page.getByTestId("question-0-mode-multi").check();

  // Default editor seeds two options; add extras until we have the requested count.
  while ((await page.getByTestId("option-label").count()) < optionLabels.length) {
    await page.getByTestId("add-option").click();
  }

  const optionInputs = page.getByTestId("option-label");
  for (let i = 0; i < optionLabels.length; i++) {
    await optionInputs.nth(i).fill(optionLabels[i]);
  }

  // Fill arity after options exist so the :max binding on the max input
  // (which is clamped to options.length) doesn't reject our target.
  // Max must be entered first when target > existing max, because the min
  // input is clamped to :max=q.maxSelections.
  const maxInput = page.getByTestId("question-0-max");
  await maxInput.fill(String(arity.max));
  const minInput = page.getByTestId("question-0-min");
  await minInput.fill(String(arity.min));

  await page.getByTestId("poll-editor-submit").click();
  await expect(page).toHaveURL(/\/polls\/[0-9a-f-]{36}$/, { timeout: 10_000 });
  await expect(page.getByTestId("poll-editor-page")).toBeVisible();
  return seed;
}

async function buildApiContext(
  playwright: typeof import("@playwright/test").default,
  baseURL: string | undefined,
  presenterContext: BrowserContext
): Promise<APIRequestContext> {
  const storage = await presenterContext.storageState();
  const csrfToken = storage.cookies.find((c) => c.name === "XSRF-TOKEN")?.value;
  expect(csrfToken, "XSRF-TOKEN cookie present after login").toBeTruthy();
  return playwright.request.newContext({
    baseURL,
    storageState: storage,
    extraHTTPHeaders: { "X-XSRF-TOKEN": csrfToken! }
  });
}

async function activateFirstQuestion(
  apiRequest: APIRequestContext,
  presenterPage: Page
): Promise<string> {
  const pollId = presenterPage.url().split("/").pop()!;
  const detailRes = await apiRequest.get(`/api/admin/polls/${pollId}`);
  expect(detailRes.status(), "fetch poll detail").toBe(200);
  const detail = (await detailRes.json()) as { questions: { id: string }[] };
  const questionId = detail.questions[0].id;

  const openRes = await apiRequest.post(`/api/admin/polls/${pollId}/open`, {
    data: { questionId }
  });
  expect(openRes.status(), "open question").toBe(200);
  return questionId;
}

test.describe.serial("per-question multi-choice arity end-to-end", () => {
  let presenterContext: BrowserContext;
  let voterContext: BrowserContext;
  let presenterPage: Page;
  let voterPage: Page;

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

  test("multi-choice ballot end-to-end", async ({ playwright, baseURL }) => {
    const unauthRequest = await playwright.request.newContext({ baseURL });
    await ensurePresenterSignedIn(presenterPage, unauthRequest);
    await unauthRequest.dispose();

    const apiRequest = await buildApiContext(playwright, baseURL, presenterContext);

    const seed = await createPollViaUi(
      presenterPage,
      "e2e-multi",
      "Which JVM features do you ship with?",
      { min: 1, max: 3 },
      ["Virtual threads", "Pattern matching", "Records"]
    );

    await voterPage.goto(`/${seed.slug}`);
    await expect(voterPage.getByTestId("poll-waiting")).toBeVisible({ timeout: 10_000 });

    await activateFirstQuestion(apiRequest, presenterPage);

    await expect(voterPage.getByTestId("poll-active")).toBeVisible({ timeout: 10_000 });
    // Multi-choice hint mentions the arity range; sanity-check that the voter
    // sees the "Pick 1 to 3" copy rather than the single-choice "Pick one".
    await expect(voterPage.getByTestId("poll-active")).toContainText(/Pick 1 to 3/i);

    const firstOpt = voterPage.getByRole("checkbox", { name: seed.optionLabels[0] });
    const secondOpt = voterPage.getByRole("checkbox", { name: seed.optionLabels[1] });
    await expect(firstOpt).toBeVisible();
    await expect(secondOpt).toBeVisible();

    await firstOpt.click();
    await secondOpt.click();
    await expect(firstOpt).toHaveAttribute("data-selected", "");
    await expect(secondOpt).toHaveAttribute("data-selected", "");

    await expect(voterPage.getByTestId("poll-submit")).toHaveText(/Submit answer/i);
    await voterPage.getByTestId("poll-submit").click();

    await expect(voterPage.getByTestId("poll-voted")).toBeVisible({ timeout: 8_000 });
    await expect(voterPage.getByTestId("poll-voted")).toContainText(/answer recorded/i);
    await expect(voterPage.getByTestId("poll-voted")).toContainText(seed.optionLabels[0]);
    await expect(voterPage.getByTestId("poll-voted")).toContainText(seed.optionLabels[1]);

    await apiRequest.dispose();
  });

  test("abstain ballot end-to-end (min=0)", async ({ playwright, baseURL }) => {
    const unauthRequest = await playwright.request.newContext({ baseURL });
    await ensurePresenterSignedIn(presenterPage, unauthRequest);
    await unauthRequest.dispose();

    const apiRequest = await buildApiContext(playwright, baseURL, presenterContext);

    const seed = await createPollViaUi(
      presenterPage,
      "e2e-abstain",
      "Optional check-in — which sessions did you attend?",
      { min: 0, max: 2 },
      ["Morning keynote", "Afternoon workshop"]
    );

    await voterPage.goto(`/${seed.slug}`);
    await expect(voterPage.getByTestId("poll-waiting")).toBeVisible({ timeout: 10_000 });

    await activateFirstQuestion(apiRequest, presenterPage);

    await expect(voterPage.getByTestId("poll-active")).toBeVisible({ timeout: 10_000 });
    await expect(voterPage.getByTestId("poll-active")).toContainText(/Optional — pick up to 2/i);

    // No option clicks — submit straight away. Button must read "Skip" while empty.
    const submit = voterPage.getByTestId("poll-submit");
    await expect(submit).toHaveText("Skip");
    await expect(submit).toBeEnabled();
    await submit.click();

    await expect(voterPage.getByTestId("poll-voted")).toBeVisible({ timeout: 8_000 });
    await expect(voterPage.getByTestId("poll-voted")).toContainText(/answer recorded/i);

    await apiRequest.dispose();
  });
});
