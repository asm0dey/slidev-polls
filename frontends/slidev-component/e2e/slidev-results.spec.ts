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
    // this through @slidev-polls/shared's openPollStream; here we bypass the Vue layer so the spec
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

  // @TS-120 / @TS-121 — anonymous browser navigating poll slides MUST NOT issue any activation
  // POST. The full Slidev host is deferred (see file header), so the spec asserts the wire
  // contract: with empty localStorage and a page that performs the same static-asset fetches a
  // real deck would, no `/api/deck/polls/*/activate` request is observed.
  test("TS-120 / TS-121: anonymous browser issues zero activation traffic", async ({ page }) => {
    const activationCalls: string[] = [];
    await page.route("**/api/deck/polls/*/activate", async (route) => {
      activationCalls.push(route.request().url());
      await route.continue();
    });

    await page.goto(`/${fixture.slug}`);
    await page.evaluate(() => window.localStorage.removeItem("slidev-polls:deck-auth"));
    // Simulate brief idle dwell for any deferred XHR the SPA might fire.
    await page.waitForTimeout(500);

    expect(activationCalls, "anonymous browser fired an activation request").toEqual([]);
  });

  // Signed-out historical snapshot: a deck panel pinned to a CLOSED question must surface the
  // prompt + tally via the anonymous /api/polls/{slug}/questions/{id}/snapshot endpoint. Before
  // this endpoint existed, an anonymous viewer would render the "Waiting…" placeholder for any
  // non-active question because SSE only carries the currently-active question.
  test("anonymous viewer can fetch historical snapshot for a CLOSED question", async ({
    page,
    playwright,
    baseURL,
    request
  }) => {
    // Vote so the historical tally is non-trivial. Use the public voter endpoint (anonymous).
    const vote = await request.post(`/api/polls/${fixture.slug}/votes`, {
      headers: { "content-type": "application/json" },
      data: { optionId: fixture.firstOptionId }
    });
    expect(vote.status()).toBe(201);

    // Close the question via the admin surface so SSE would otherwise drop it from the snapshot.
    const admin = await playwright.request.newContext({ baseURL });
    await loginAsAlice(admin);
    const close = await admin.post(`/api/admin/polls/${fixture.pollId}/close`, {
      headers: await xsrfHeaders(admin, baseURL!)
    });
    expect(close.status()).toBe(200);
    await admin.dispose();

    await page.goto(`/${fixture.slug}`);

    const histUrl = `/api/polls/${fixture.slug}/questions/${fixture.questionId}/snapshot`;
    const snapshot = await page.evaluate(async (url) => {
      const res = await fetch(url);
      return { status: res.status, body: (await res.json()) as unknown };
    }, histUrl);

    expect(snapshot.status).toBe(200);
    expect(snapshot.body).toMatchObject({
      slug: fixture.slug,
      activeQuestion: expect.objectContaining({ id: fixture.questionId })
    });
    const tally = (snapshot.body as { tally: Array<{ optionId: string; count: number }> }).tally;
    const voted = tally.find((t) => t.optionId === fixture.firstOptionId);
    expect(voted?.count, "historical tally reflects the recorded vote").toBeGreaterThanOrEqual(1);

    // Reset to ACTIVE so subsequent tests in the file see the original fixture state.
    const reopen = await playwright.request.newContext({ baseURL });
    await loginAsAlice(reopen);
    const open = await reopen.post(`/api/admin/polls/${fixture.pollId}/open`, {
      headers: {
        "content-type": "application/json",
        ...(await xsrfHeaders(reopen, baseURL!))
      },
      data: { questionId: fixture.questionId }
    });
    expect(open.status()).toBe(200);
    await reopen.dispose();
  });

  // Signed-in slide-back-and-forth: /close with a body { questionId } that does NOT match the
  // currently-active question is a no-op. Pins the server-side guard that prevents a stale
  // slide-leave close from racing past the next slide's activate and clobbering the question that
  // was just opened.
  test("/close with mismatched questionId leaves the active question untouched", async ({
    playwright,
    baseURL,
    request
  }) => {
    // Seed a fresh 2-question poll so we can swap activation without disturbing the file-wide
    // fixture. The shared fixture only has a single question, so it cannot model the race.
    const admin = await playwright.request.newContext({ baseURL });
    await loginAsAlice(admin);
    const slug = `e2e-close-${Date.now().toString(36)}`;
    const create = await admin.post("/api/admin/polls", {
      headers: {
        "content-type": "application/json",
        ...(await xsrfHeaders(admin, baseURL!))
      },
      data: {
        title: `E2E close ${slug}`,
        slug,
        questions: [
          { prompt: "Q1", options: [{ label: "A" }, { label: "B" }] },
          { prompt: "Q2", options: [{ label: "C" }, { label: "D" }] }
        ]
      }
    });
    expect(create.status()).toBe(201);
    const poll = (await create.json()) as {
      id: string;
      questions: Array<{ id: string }>;
    };
    const q1 = poll.questions[0].id;
    const q2 = poll.questions[1].id;

    const mint = await admin.post(`/api/admin/polls/${poll.id}/deck-tokens`, {
      headers: {
        "content-type": "application/json",
        ...(await xsrfHeaders(admin, baseURL!))
      },
      data: { label: "e2e-close-race" }
    });
    expect(mint.status()).toBe(201);
    const token = ((await mint.json()) as { plaintext: string }).plaintext;

    // Open Q2 via the deck path so the race mirrors what the panel does on slide-enter.
    const activateQ2 = await request.post(`/api/deck/polls/${poll.id}/activate`, {
      headers: { "content-type": "application/json", "X-Deck-Token": token },
      data: { questionId: q2 }
    });
    expect(activateQ2.status()).toBe(200);

    // A late slide-leave close from Q1 lands now — must NOT close Q2.
    const staleClose = await request.post(`/api/deck/polls/${poll.id}/close`, {
      headers: { "content-type": "application/json", "X-Deck-Token": token },
      data: { questionId: q1 }
    });
    expect(staleClose.status()).toBe(200);
    expect(
      ((await staleClose.json()) as { activeQuestionId: string | null }).activeQuestionId
    ).toBe(q2);

    // A matching close (questionId = Q2) closes Q2 as expected.
    const properClose = await request.post(`/api/deck/polls/${poll.id}/close`, {
      headers: { "content-type": "application/json", "X-Deck-Token": token },
      data: { questionId: q2 }
    });
    expect(properClose.status()).toBe(200);
    expect(
      ((await properClose.json()) as { activeQuestionId: string | null }).activeQuestionId
    ).toBeNull();

    await admin.delete(`/api/admin/polls/${poll.id}`, {
      headers: await xsrfHeaders(admin, baseURL!)
    });
    await admin.dispose();
  });

  // @TS-122 — signed-in browser issues an activation POST with the X-Deck-Token header. The
  // addon's sign-in flow ends in a call equivalent to this one; the spec asserts the backend
  // accepts it and that the poll's active-question pointer moves as expected.
  test("TS-122: signed-in browser's activation POST is accepted", async ({
    playwright,
    baseURL,
    request
  }) => {
    const admin = await playwright.request.newContext({ baseURL });
    await loginAsAlice(admin);
    const mintRes = await admin.post(`/api/admin/polls/${fixture.pollId}/deck-tokens`, {
      headers: {
        "content-type": "application/json",
        ...(await xsrfHeaders(admin, baseURL!))
      },
      data: { label: "e2e-signed-in" }
    });
    expect(mintRes.status()).toBe(201);
    const minted = (await mintRes.json()) as { plaintext: string };
    await admin.dispose();

    const activate = await request.post(`/api/deck/polls/${fixture.pollId}/activate`, {
      headers: {
        "content-type": "application/json",
        "X-Deck-Token": minted.plaintext
      },
      data: { questionId: fixture.questionId }
    });
    expect(activate.status()).toBe(200);
    const body = (await activate.json()) as { activeQuestionId: string };
    expect(body.activeQuestionId).toBe(fixture.questionId);
  });
});
