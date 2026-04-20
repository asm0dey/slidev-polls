import { expect, test, type APIRequestContext } from "@playwright/test";

// Smoke test for the slidev-addon surface. Deliberately narrow: the full Slidev host is out of
// scope for this feature (tasks.md clarification 2026-04-19 — "Addon consumed locally from the
// monorepo; publish strategy deferred"), so the real e2e contract is the SSE wire protocol the
// addon relies on. This spec:
//
//   1. seeds a poll through the admin HTTP surface (login / create / activate)
//   2. page.goto()'s the backend's SPA shell so the test page holds a same-origin context
//   3. opens an EventSource from that page against /api/polls/{slug}/stream
//   4. asserts that a "snapshot" event with the expected active question arrives inside 5s
//   5. casts a vote and asserts a follow-up "tally" event arrives inside 3s (the @TS-030 budget
//      in the voter path; slightly padded for browser EventSource decode overhead)
//
// The VitestSuite in components/PollResults.test.ts (T105) covers the render / stray-tally /
// paused-badge / reconnect client behaviour — this Playwright smoke is the last-mile assertion
// that the same contract holds end-to-end from a real browser against a real backend.

type Fixture = {
  slug: string;
  pollId: string;
  questionId: string;
  firstOptionId: string;
};

// Admin surface is CSRF-protected (see BUG-004 / BUG-006): raw APIRequestContext has to echo the
// XSRF-TOKEN cookie as X-XSRF-TOKEN on state-changing calls or CsrfFilter returns 403.
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

async function seedPoll(request: APIRequestContext, baseURL: string): Promise<Fixture> {
  const slug = `e2e-slidev-${Date.now().toString(36)}`;
  const create = await request.post("/api/admin/polls", {
    headers: {
      "content-type": "application/json",
      ...(await xsrfHeaders(request, baseURL))
    },
    data: {
      title: `E2E slidev ${slug}`,
      slug,
      questions: [
        { prompt: "Which build tool?", options: [{ label: "Maven" }, { label: "Gradle" }] }
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
  const firstOptionId = body.questions[0].options[0].id;
  const open = await request.post(`/api/admin/polls/${body.id}/open`, {
    headers: {
      "content-type": "application/json",
      ...(await xsrfHeaders(request, baseURL))
    },
    data: { questionId }
  });
  expect(open.status(), "activate question").toBe(200);
  return { slug: body.slug, pollId: body.id, questionId, firstOptionId };
}

async function deletePoll(request: APIRequestContext, baseURL: string, pollId: string) {
  await request.delete(`/api/admin/polls/${pollId}`, {
    headers: await xsrfHeaders(request, baseURL)
  });
}

test.describe("slidev addon sse smoke", () => {
  let fixture: Fixture;

  test.beforeAll(async ({ playwright, baseURL }) => {
    const request = await playwright.request.newContext({ baseURL });
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

  test("SSE stream delivers snapshot and tally to the browser", async ({ page, request }) => {
    // Land on the voter SPA shell so the subsequent EventSource runs same-origin.
    await page.goto(`/${fixture.slug}`);

    // Open an EventSource from the page and wait for the initial snapshot. The slidev addon does
    // this through @polls/shared's openPollStream; here we bypass the Vue layer so the spec
    // survives any future change to the component's internals.
    const snapshot = await page.evaluate((slug) => {
      return new Promise<unknown>((resolve, reject) => {
        const es = new EventSource(`/api/polls/${slug}/stream`);
        const timeout = window.setTimeout(() => {
          es.close();
          reject(new Error("timed out waiting for snapshot"));
        }, 5000);
        es.addEventListener("snapshot", (e) => {
          window.clearTimeout(timeout);
          const data = JSON.parse((e as MessageEvent).data);
          es.close();
          resolve(data);
        });
      });
    }, fixture.slug);

    expect(snapshot).toMatchObject({
      slug: fixture.slug,
      activeQuestion: expect.objectContaining({ id: fixture.questionId })
    });

    // Fire a vote through the backend and watch for the follow-up tally event on a fresh
    // EventSource (the previous one was closed on receipt). The contract: the tally carries the
    // new absolute count for the voted option.
    const tallyPromise = page.evaluate(
      ({ slug, questionId }) => {
        return new Promise<unknown>((resolve, reject) => {
          const es = new EventSource(`/api/polls/${slug}/stream`);
          const timeout = window.setTimeout(() => {
            es.close();
            reject(new Error("timed out waiting for tally"));
          }, 3000);
          es.addEventListener("tally", (e) => {
            const data = JSON.parse((e as MessageEvent).data);
            if (data.questionId === questionId) {
              window.clearTimeout(timeout);
              es.close();
              resolve(data);
            }
          });
        });
      },
      { slug: fixture.slug, questionId: fixture.questionId }
    );

    // Give the second EventSource a moment to register with the hub before voting.
    await page.waitForTimeout(200);
    const vote = await request.post(`/api/polls/${fixture.slug}/votes`, {
      headers: { "content-type": "application/json" },
      data: { optionId: fixture.firstOptionId }
    });
    expect(vote.status()).toBe(201);

    const tally = (await tallyPromise) as { optionId: string; count: number };
    expect(tally.optionId).toBe(fixture.firstOptionId);
    expect(tally.count).toBeGreaterThanOrEqual(1);
  });
});
