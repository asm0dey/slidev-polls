import { expect, test, type APIRequestContext } from "@playwright/test";

// Regression e2e (collaborator deck sign-in): a user who only COLLABORATES on a poll
// (owns none) must be able to authenticate at POST /api/deck/auth/login and receive a
// deck token scoped to the shared poll. Before the fix the login picked from owner-only
// polls, so a pure collaborator got 401 {"code":"AUTH_REQUIRED"}.
//
// Lives under voter/e2e because that project owns the working harness + compose.dev.yml
// provisioning (`task test:e2e:voter`); the test is pure backend API and needs no SPA.

const COLLAB = { username: "collab-bob", password: "collab-bobs-password-12" };

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

async function ensureAdminBootstrapped(request: APIRequestContext) {
  const status = await request.get("/api/admin/setup/status");
  expect(status.status(), "setup status probe").toBe(200);
  const { setupRequired } = (await status.json()) as { setupRequired: boolean };
  if (!setupRequired) return;
  const res = await request.post("/api/admin/setup", {
    headers: { "content-type": "application/json" },
    data: { username: "alice", password: "correct-horse" }
  });
  // A parallel spec may win the first-run race (409 SETUP_LOCKED); either outcome leaves
  // admin_user populated, which is all this spec needs.
  expect([201, 409], "first-run setup").toContain(res.status());
}

async function loginAsAlice(request: APIRequestContext) {
  const res = await request.post("/api/admin/login", {
    data: { username: "alice", password: "correct-horse" },
    headers: { "content-type": "application/json" }
  });
  expect(res.status(), "alice login").toBeLessThan(300);
}

test.describe("deck sign-in for a collaborator", () => {
  let pollId: string;

  test.beforeAll(async ({ playwright, baseURL }) => {
    const request = await playwright.request.newContext({ baseURL });
    await ensureAdminBootstrapped(request);
    await loginAsAlice(request);
    const headers = {
      "content-type": "application/json",
      ...(await xsrfHeaders(request, baseURL!))
    };

    // The collaborator account (idempotent: 201 fresh, 409 if a prior run created it).
    const created = await request.post("/api/admin/users", { headers, data: COLLAB });
    expect([201, 409], "create collaborator user").toContain(created.status());

    // A poll owned by alice — collab-bob owns nothing, so this is the only poll visible to it.
    const pollRes = await request.post("/api/admin/polls", {
      headers,
      data: {
        title: "Collaborator deck poll",
        slug: `e2e-collab-deck-${Date.now()}`,
        questions: [{ prompt: "Q1?", options: [{ label: "A" }, { label: "B" }] }]
      }
    });
    expect(pollRes.status(), "create poll").toBe(201);
    pollId = (await pollRes.json()).id;

    const addRes = await request.post(`/api/admin/polls/${pollId}/collaborators`, {
      headers,
      data: { username: COLLAB.username }
    });
    expect([201, 409], "add collaborator").toContain(addRes.status());
    await request.dispose();
  });

  test.afterAll(async ({ playwright, baseURL }) => {
    if (!pollId) return;
    const request = await playwright.request.newContext({ baseURL });
    await loginAsAlice(request);
    const headers = {
      "content-type": "application/json",
      ...(await xsrfHeaders(request, baseURL!))
    };
    const res = await request.delete(`/api/admin/polls/${pollId}`, { headers });
    expect([204, 404], "cleanup poll").toContain(res.status());
    await request.dispose();
  });

  test("collaborator who owns no poll signs into the deck and gets a token for the shared poll", async ({
    request
  }) => {
    const login = await request.post("/api/deck/auth/login", {
      headers: { "content-type": "application/json" },
      data: { username: COLLAB.username, password: COLLAB.password }
    });
    // Before the fix this returned 401 {"code":"AUTH_REQUIRED"} because the login selected
    // from owner-only polls and the collaborator owns none.
    expect(login.status(), await login.text()).toBe(200);
    const body = (await login.json()) as { token: string; pollId: string };
    expect(body.pollId, "minted token scoped to the shared poll").toBe(pollId);
    expect(typeof body.token, "token plaintext returned").toBe("string");

    // The minted token must authenticate the follow-up /me probe and resolve to the same poll.
    const me = await request.get("/api/deck/auth/me", {
      headers: { "X-Deck-Token": body.token }
    });
    expect(me.status(), "deck /me with minted token").toBe(200);
    expect((await me.json()).pollId).toBe(pollId);
  });
});
