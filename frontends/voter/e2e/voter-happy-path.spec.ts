import { expect, test, type APIRequestContext } from "@playwright/test";

// End-to-end smoke for the anonymous voter path. Seeds a poll via the admin HTTP surface so the
// test is self-contained against any running backend (no DB poking, no SQL fixtures): login as
// alice, create a poll with a randomised slug, activate its question, run the voter flow in a
// real browser, then delete the poll on teardown.
//
// The test is gated on a running backend listening at PW_BASE_URL (defaults to
// http://localhost:8080). CI wires this up by booting the Spring Boot JAR plus Postgres before
// invoking `bun --cwd frontends/voter run e2e`; locally, `scripts/dev.sh` does the same.

type Fixture = {
  slug: string;
  pollId: string;
  questionId: string;
  firstOptionLabel: string;
};

async function loginAsAlice(request: APIRequestContext) {
  const res = await request.post("/api/admin/login", {
    data: { username: "alice", password: "correct-horse" },
    headers: { "content-type": "application/json" }
  });
  expect(res.status(), "alice login").toBeLessThan(300);
}

async function seedPoll(request: APIRequestContext): Promise<Fixture> {
  // A unique slug per run avoids collisions across re-runs against the same DB.
  const slug = `e2e-voter-${Date.now().toString(36)}`;
  const create = await request.post("/api/admin/polls", {
    headers: { "content-type": "application/json" },
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
    headers: { "content-type": "application/json" },
    data: { questionId }
  });
  expect(open.status(), "activate question").toBe(200);

  return { slug: body.slug, pollId: body.id, questionId, firstOptionLabel };
}

async function deletePoll(request: APIRequestContext, pollId: string) {
  await request.delete(`/api/admin/polls/${pollId}`);
}

test.describe("voter happy path", () => {
  let fixture: Fixture;

  test.beforeAll(async ({ playwright, baseURL }) => {
    const request = await playwright.request.newContext({ baseURL });
    await loginAsAlice(request);
    fixture = await seedPoll(request);
    await request.dispose();
  });

  test.afterAll(async ({ playwright, baseURL }) => {
    const request = await playwright.request.newContext({ baseURL });
    await loginAsAlice(request);
    await deletePoll(request, fixture.pollId);
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
