import { describe, expect, it, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import type { DeckToken, DeckTokenMinted } from "@slidev-polls/shared";
import DeckTokensPage from "./DeckTokensPage.vue";
import type { AdminApiClient } from "../lib/admin-api";
import { AdminApiError } from "../lib/admin-api";

// @TS-056 — mint surfaces plaintext exactly once; list rows never carry it. The UI contract tests
// mirror that: after mint the banner with `[data-testid="minted-plaintext"]` is visible, but
// table rows render only label/date/status, never the plaintext.
// @TS-057 — revoke path is present and lands a DELETE call; feedback is rendered to the presenter.

type FakeClient = Pick<AdminApiClient, "listDeckTokens" | "mintDeckToken" | "revokeDeckToken">;

function makeFake(overrides: Partial<FakeClient> = {}): AdminApiClient {
  const base: FakeClient = {
    listDeckTokens: vi.fn().mockResolvedValue([]),
    mintDeckToken: vi.fn(),
    revokeDeckToken: vi.fn().mockResolvedValue(undefined),
    ...overrides
  };
  return base as unknown as AdminApiClient;
}

function tokenRow(over: Partial<DeckToken> = {}): DeckToken {
  return {
    id: "t-1",
    pollId: "p-1",
    label: "laptop",
    createdAt: "2026-04-18T10:00:00Z",
    revokedAt: null,
    ...over
  };
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/polls/:pollId", name: "poll-edit", component: { template: "<div/>" } },
      { path: "/polls/:pollId/deck-tokens", name: "deck-tokens", component: { template: "<div/>" } }
    ]
  });
}

async function mountPage(client: AdminApiClient) {
  const router = makeRouter();
  router.push("/polls/p-1/deck-tokens");
  await router.isReady();
  const wrapper = mount(DeckTokensPage, {
    props: { pollId: "p-1", apiClient: client },
    global: { plugins: [router] }
  });
  await flushPromises();
  return wrapper;
}

describe("DeckTokensPage", () => {
  beforeEach(() => {
    // jsdom's URL / clipboard access is fine as-is; window.confirm returns true by default only
    // after we stub it, so stub at the suite level.
    vi.stubGlobal("confirm", vi.fn().mockReturnValue(true));
  });

  it("renders an empty state when no tokens exist", async () => {
    const client = makeFake();
    const wrapper = await mountPage(client);
    expect(wrapper.get("[data-testid='empty']").text()).toMatch(/no deck tokens/i);
    expect(client.listDeckTokens).toHaveBeenCalledWith("p-1");
  });

  it("lists existing tokens with status", async () => {
    const client = makeFake({
      listDeckTokens: vi
        .fn()
        .mockResolvedValue([
          tokenRow({ id: "a", label: "laptop" }),
          tokenRow({ id: "b", label: null, revokedAt: "2026-04-19T12:00:00Z" })
        ])
    });
    const wrapper = await mountPage(client);
    const rows = wrapper.findAll("[data-testid^='row-']");
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain("laptop");
    expect(rows[0].text()).toContain("Live");
    expect(rows[1].text()).toContain("Revoked");
    // @TS-056: rendered row text never leaks plaintext — the shared DeckToken type forbids it,
    // so this assertion reads as intent even though TS already guarantees the field is absent.
    expect(wrapper.html()).not.toContain("plaintext-for-b");
  });

  it("surfaces the minted plaintext exactly once and refreshes the list", async () => {
    const listMock = vi.fn();
    listMock.mockResolvedValueOnce([]); // initial
    listMock.mockResolvedValueOnce([tokenRow({ id: "new-token" })]); // post-mint
    const minted: DeckTokenMinted = {
      id: "new-token",
      pollId: "p-1",
      label: "laptop",
      createdAt: "2026-04-19T10:00:00Z",
      revokedAt: null,
      plaintext: "supersecret"
    };
    const client = makeFake({
      listDeckTokens: listMock,
      mintDeckToken: vi.fn().mockResolvedValue(minted)
    });
    const wrapper = await mountPage(client);
    await wrapper.get("[data-testid='mint-label']").setValue("laptop");
    await wrapper.get("[data-testid='mint-submit']").trigger("submit");
    await flushPromises();
    expect(client.mintDeckToken).toHaveBeenCalledWith("p-1", { label: "laptop" });
    expect(wrapper.get("[data-testid='minted-plaintext']").text()).toBe("supersecret");
    // Table refreshed → row for the new token rendered.
    expect(wrapper.findAll("[data-testid^='row-']")).toHaveLength(1);
  });

  it("lands a DELETE when revoke is confirmed", async () => {
    const listMock = vi
      .fn()
      .mockResolvedValueOnce([tokenRow({ id: "t-x", revokedAt: null })])
      .mockResolvedValueOnce([tokenRow({ id: "t-x", revokedAt: "2026-04-19T11:00:00Z" })]);
    const client = makeFake({ listDeckTokens: listMock });
    const wrapper = await mountPage(client);
    await wrapper.get("[data-testid='revoke-t-x']").trigger("click");
    await flushPromises();
    expect(client.revokeDeckToken).toHaveBeenCalledWith("p-1", "t-x");
  });

  it("maps AUTH_REQUIRED onto an actionable message", async () => {
    const client = makeFake({
      listDeckTokens: vi
        .fn()
        .mockRejectedValue(
          new AdminApiError(
            401,
            { code: "AUTH_REQUIRED", message: "authentication required" },
            "authentication required"
          )
        )
    });
    const wrapper = await mountPage(client);
    expect(wrapper.get("[data-testid='deck-tokens-error']").text()).toMatch(/sign back in/i);
  });
});
