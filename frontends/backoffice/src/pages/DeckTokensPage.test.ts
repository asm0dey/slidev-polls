import { describe, expect, it, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import type { DeckToken } from "@slidev-polls/shared";
import DeckTokensPage from "./DeckTokensPage.vue";
import type { AdminApiClient } from "../lib/admin-api";
import { AdminApiError } from "../lib/admin-api";

// @TS-056 — minting now happens server-side on POST /api/deck/auth/login, never from this page.
// The UI is a read-only list of issued tokens plus a per-row revoke. The plaintext bearer never
// leaves the backend on this surface, so no `[data-testid="minted-banner"]` element can ever
// appear here.
// @TS-057 — revoke path is present and lands a DELETE call; feedback is rendered to the presenter.

type FakeClient = Pick<AdminApiClient, "listDeckTokens" | "revokeDeckToken">;

function makeFake(overrides: Partial<FakeClient> = {}): AdminApiClient {
  const base: FakeClient = {
    listDeckTokens: vi.fn().mockResolvedValue([]),
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

async function mountDeckTokens(rows: DeckToken[]) {
  const client = makeFake({ listDeckTokens: vi.fn().mockResolvedValue(rows) });
  const router = makeRouter();
  router.push("/polls/p-1/deck-tokens");
  await router.isReady();
  const wrapper = mount(DeckTokensPage, {
    props: { pollId: "p-1", apiClient: client },
    global: { plugins: [router] }
  });
  await flushPromises();
  return { wrapper, client };
}

describe("DeckTokensPage", () => {
  // jsdom does not implement HTMLDialogElement showModal()/close() — shim them.
  beforeEach(() => {
    if (!HTMLDialogElement.prototype.showModal) {
      HTMLDialogElement.prototype.showModal = function () {
        this.setAttribute("open", "");
      };
    }
    if (!HTMLDialogElement.prototype.close) {
      HTMLDialogElement.prototype.close = function () {
        this.removeAttribute("open");
      };
    }
  });

  it("has no manual mint UI", async () => {
    const { wrapper } = await mountDeckTokens([]);
    expect(wrapper.find('[data-testid="mint-form"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="mint-submit"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="mint-label"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="deck-token-create"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="minted-banner"]').exists()).toBe(false);
  });

  it("empty state explains where deck tokens come from", async () => {
    const { wrapper } = await mountDeckTokens([]);
    const empty = wrapper.get('[data-testid="empty"]');
    expect(empty.text()).toContain("Sign in from your deck");
  });

  it("lists existing tokens with a revoke action", async () => {
    const { wrapper } = await mountDeckTokens([
      tokenRow({ id: "tk1", label: "laptop", revokedAt: null })
    ]);
    expect(wrapper.find('[data-testid="row-tk1"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="revoke-tk1"]').exists()).toBe(true);
  });

  it("renders Live/Revoked status per row", async () => {
    const { wrapper } = await mountDeckTokens([
      tokenRow({ id: "a", label: "laptop" }),
      tokenRow({ id: "b", label: null, revokedAt: "2026-04-19T12:00:00Z" })
    ]);
    const rows = wrapper.findAll("[data-testid^='row-']");
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain("laptop");
    expect(rows[0].text()).toContain("Live");
    expect(rows[1].text()).toContain("Revoked");
  });

  it("lands a DELETE when revoke is confirmed", async () => {
    const listMock = vi
      .fn()
      .mockResolvedValueOnce([tokenRow({ id: "t-x", revokedAt: null })])
      .mockResolvedValueOnce([tokenRow({ id: "t-x", revokedAt: "2026-04-19T11:00:00Z" })]);
    const revokeDeckToken = vi.fn().mockResolvedValue(undefined);
    const client = makeFake({ listDeckTokens: listMock, revokeDeckToken });
    const router = makeRouter();
    router.push("/polls/p-1/deck-tokens");
    await router.isReady();
    const wrapper = mount(DeckTokensPage, {
      props: { pollId: "p-1", apiClient: client },
      global: { plugins: [router] }
    });
    await flushPromises();
    await wrapper.get("[data-testid='revoke-t-x']").trigger("click");
    await flushPromises();
    await wrapper.get('[data-testid="confirm-dialog-confirm"]').trigger("click");
    await flushPromises();
    expect(revokeDeckToken).toHaveBeenCalledWith("p-1", "t-x");
  });

  it("revoke opens ConfirmDialog instead of window.confirm", async () => {
    const { wrapper } = await mountDeckTokens([
      tokenRow({ id: "tk1", label: "laptop", revokedAt: null })
    ]);
    await wrapper.get('[data-testid="revoke-tk1"]').trigger("click");
    expect(wrapper.find('[data-testid="confirm-dialog"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="confirm-dialog-confirm"]').exists()).toBe(true);
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
    const router = makeRouter();
    router.push("/polls/p-1/deck-tokens");
    await router.isReady();
    const wrapper = mount(DeckTokensPage, {
      props: { pollId: "p-1", apiClient: client },
      global: { plugins: [router] }
    });
    await flushPromises();
    expect(wrapper.get("[data-testid='deck-tokens-error']").text()).toMatch(/sign back in/i);
  });
});
