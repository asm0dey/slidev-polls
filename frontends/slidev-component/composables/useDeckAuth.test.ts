import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { __resetUseDeckAuthForTests, useDeckAuth } from "./useDeckAuth";
import {
  configureDeckAuthBackend,
  __resetConfiguredBackendForTests
} from "./configureDeckAuthBackend";

// Tests mirror the scenarios from
// specs/002-presenter-auth-gating/tests/features/deck-auth-signin.feature and
// activation-gating.feature. Gherkin Given/When/Then is reproduced as comments per
// Constitution Principle VII (no BDD runner).

const STORAGE_KEY = "slidev-polls:deck-auth";

function asResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" }
  });
}

function makeMemoryStorage(): Storage {
  const store = new Map<string, string>();
  return {
    get length() {
      return store.size;
    },
    clear: () => store.clear(),
    getItem: (key: string) => (store.has(key) ? store.get(key)! : null),
    key: (i: number) => Array.from(store.keys())[i] ?? null,
    removeItem: (key: string) => {
      store.delete(key);
    },
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    }
  };
}

describe("useDeckAuth composable", () => {
  beforeEach(() => {
    Object.defineProperty(window, "localStorage", {
      value: makeMemoryStorage(),
      configurable: true
    });
    __resetUseDeckAuthForTests();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // @TS-101 — Given anonymous start, When signIn(token) succeeds, Then status transitions
  // anonymous → signed-in-pending → signed-in and localStorage stores the full auth payload.
  it("TS-101: signIn transitions to signed-in and persists localStorage", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      asResponse(200, {
        tokenId: "11111111-1111-1111-1111-111111111111",
        pollId: "22222222-2222-2222-2222-222222222222",
        label: "Laptop"
      })
    );
    vi.stubGlobal("fetch", fetchMock);

    const auth = useDeckAuth();
    expect(auth.status.value).toBe("anonymous");

    const statuses: string[] = [];
    const stop = vi.fn();
    // capture transitions by polling after each microtask
    const promise = auth.signIn("dtk-valid-token-value-long");
    statuses.push(auth.status.value);
    await promise;
    statuses.push(auth.status.value);

    expect(statuses).toContain("signed-in-pending");
    expect(auth.status.value).toBe("signed-in");

    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw).not.toBeNull();
    const stored = JSON.parse(raw!);
    expect(stored.token).toBe("dtk-valid-token-value-long");
    expect(stored.tokenId).toBe("11111111-1111-1111-1111-111111111111");
    expect(stored.pollId).toBe("22222222-2222-2222-2222-222222222222");
    expect(stored.label).toBe("Laptop");
    expect(typeof stored.verifiedAt).toBe("string");
    stop();
  });

  // @TS-103 — Given localStorage preloaded, When useDeckAuth() init runs, Then status starts
  // signed-in-pending and flips to signed-in once the reload-time verify 200 lands.
  it("TS-103: reload rehydrates via signed-in-pending → signed-in without prompting", async () => {
    window.localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        token: "dtk-valid-token-value-long",
        tokenId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        pollId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        label: "Laptop",
        verifiedAt: "2026-04-20T00:00:00.000Z"
      })
    );
    const fetchMock = vi.fn().mockResolvedValue(
      asResponse(200, {
        tokenId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        pollId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        label: "Laptop"
      })
    );
    vi.stubGlobal("fetch", fetchMock);

    const auth = useDeckAuth();
    expect(auth.status.value).toBe("signed-in-pending");
    // flush the background verify
    await nextTick();
    await new Promise((r) => setTimeout(r, 0));
    await nextTick();
    expect(auth.status.value).toBe("signed-in");
    expect(auth.state.value.label).toBe("Laptop");
  });

  // @TS-104 — Given signed-in, When signOut(), Then status → anonymous, localStorage cleared,
  // zero backend calls.
  it("TS-104: signOut is local-only and clears storage", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      asResponse(200, {
        tokenId: "11111111-1111-1111-1111-111111111111",
        pollId: "22222222-2222-2222-2222-222222222222",
        label: "Laptop"
      })
    );
    vi.stubGlobal("fetch", fetchMock);

    const auth = useDeckAuth();
    await auth.signIn("dtk-valid-token-value-long");
    expect(auth.status.value).toBe("signed-in");

    const callsBefore = fetchMock.mock.calls.length;
    auth.signOut();
    expect(auth.status.value).toBe("anonymous");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
    // no new fetch call after sign-out
    expect(fetchMock.mock.calls.length).toBe(callsBefore);
  });

  // @TS-105 — Given garbage token, When verify returns 401 DECK_TOKEN_INVALID, Then status
  // stays anonymous and message is "credential not recognised".
  it("TS-105: signIn with garbage keeps anonymous + authentication-failure message", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(asResponse(401, { code: "DECK_TOKEN_INVALID", message: "invalid" }));
    vi.stubGlobal("fetch", fetchMock);

    const auth = useDeckAuth();
    await auth.signIn("garbage-but-long-enough-to-pass");
    expect(auth.status.value).toBe("anonymous");
    expect(auth.message.value).toBe("credential not recognised");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  // @BUG-002 — Given valid admin-equivalent credentials, When signInWithCredentials(user, pass)
  // succeeds, Then status transitions to signed-in, localStorage carries the minted token plus
  // scope, and the POST hits /api/deck/auth/login.
  it("BUG-002: signInWithCredentials persists minted token and flips status to signed-in", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      asResponse(200, {
        token: "dtk-minted-plaintext-value",
        tokenId: "11111111-1111-1111-1111-111111111111",
        pollId: "22222222-2222-2222-2222-222222222222",
        label: "deck"
      })
    );
    vi.stubGlobal("fetch", fetchMock);

    const auth = useDeckAuth();
    await auth.signInWithCredentials("alice", "correct-horse");
    expect(auth.status.value).toBe("signed-in");

    const call = fetchMock.mock.calls[0];
    expect(call[0]).toContain("/api/deck/auth/login");
    expect(call[1].method).toBe("POST");
    expect(JSON.parse(call[1].body)).toEqual({
      username: "alice",
      password: "correct-horse"
    });

    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw).not.toBeNull();
    const stored = JSON.parse(raw!);
    expect(stored.token).toBe("dtk-minted-plaintext-value");
    expect(stored.tokenId).toBe("11111111-1111-1111-1111-111111111111");
    expect(stored.pollId).toBe("22222222-2222-2222-2222-222222222222");
    expect(stored.label).toBe("deck");
  });

  // @BUG-002 — Invalid credentials → 401 → anonymous + "credential not recognised".
  it("BUG-002: signInWithCredentials on 401 stays anonymous with auth-failure message", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        asResponse(401, { code: "AUTH_REQUIRED", message: "authentication required" })
      );
    vi.stubGlobal("fetch", fetchMock);

    const auth = useDeckAuth();
    await auth.signInWithCredentials("alice", "wrong");
    expect(auth.status.value).toBe("anonymous");
    expect(auth.message.value).toBe("credential not recognised");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  // @TS-106 — Given transport failure, When signIn fetch rejects, Then status stays anonymous
  // and message is "couldn't reach server" (distinct from the TS-105 message).
  it("TS-106: signIn with network error surfaces the transport message", async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error("network down"));
    vi.stubGlobal("fetch", fetchMock);

    const auth = useDeckAuth();
    await auth.signIn("dtk-valid-token-value-long");
    expect(auth.status.value).toBe("anonymous");
    expect(auth.message.value).toBe("couldn't reach server");
    expect(auth.message.value).not.toBe("credential not recognised");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});

describe("dynamic backend URL", () => {
  beforeEach(() => {
    __resetUseDeckAuthForTests();
    __resetConfiguredBackendForTests();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it("uses the URL set by configureDeckAuthBackend, even when the singleton was created earlier with a different baseUrl", async () => {
    // Singleton is created first (e.g. by the nav control on a non-poll slide)
    const auth = useDeckAuth();
    configureDeckAuthBackend("http://localhost:8080");

    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          token: "T".repeat(40),
          tokenId: "id",
          pollId: "pid",
          label: "demo"
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      )
    );

    await auth.signInWithCredentials("alice", "p");

    expect(fetchSpy).toHaveBeenCalledWith(
      "http://localhost:8080/api/deck/auth/login",
      expect.any(Object)
    );
  });
});
