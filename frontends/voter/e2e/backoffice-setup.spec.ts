import { expect, test } from "@playwright/test";

// First-run setup smoke for the backoffice admin bootstrap (V6+). The voter playwright config
// owns the only working e2e harness in this repo, so the spec lives under voter/e2e even though
// the endpoints belong to the backoffice surface — running it under voter-chromium reuses the
// shared baseURL + compose.dev.yml provisioning from `task test:e2e:voter` without inventing a
// second playwright project just for this one spec.
//
// The DB persists across local runs, so once any prior run (or voter-happy-path.spec.ts) has
// already provisioned admin_user, this spec gracefully skips. To exercise it intentionally, run
// against a fresh `compose.dev.yml down -v` volume.

test.describe("backoffice first-run setup", () => {
  test("provisions the first admin and locks subsequent setup attempts", async ({ request }) => {
    const status = await request.get("/api/admin/setup/status");
    expect(status.status(), "setup status").toBe(200);
    const { setupRequired } = (await status.json()) as { setupRequired: boolean };
    test.skip(!setupRequired, "admin_user already populated; skipping first-run setup spec");

    const setup = await request.post("/api/admin/setup", {
      headers: { "content-type": "application/json" },
      data: {
        username: "alice",
        password: "correct-horse",
        displayName: "Alice Presenter"
      }
    });
    expect(setup.status(), "setup create").toBe(201);

    const after = await request.get("/api/admin/setup/status");
    expect(after.status(), "post-setup status").toBe(200);
    expect(await after.json()).toEqual({ setupRequired: false });

    // A second setup attempt must lock with SETUP_LOCKED, regardless of payload.
    const locked = await request.post("/api/admin/setup", {
      headers: { "content-type": "application/json" },
      data: {
        username: "bob",
        password: "another-strong-pw",
        displayName: "Bob Presenter"
      }
    });
    expect(locked.status(), "second setup attempt").toBe(409);
    const lockedBody = (await locked.json()) as { code?: string };
    expect(lockedBody.code).toBe("SETUP_LOCKED");
  });
});
