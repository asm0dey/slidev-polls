import { describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import type { Poll } from "@slidev-polls/shared";
import PollListPage from "./PollListPage.vue";
import type { AdminApiClient } from "../lib/admin-api";
import { AdminApiError } from "../lib/admin-api";

// Match @TS-002 ("poll appears in her poll list" + "poll exposes a join link")
// and @TS-001 (auth-failure surfaces an actionable error, not a stack trace).

type FakeClient = Pick<AdminApiClient, "listPolls" | "deletePoll" | "qrUrl">;

function makeFake(overrides: Partial<FakeClient> = {}): AdminApiClient {
  const base: FakeClient = {
    listPolls: vi.fn().mockResolvedValue([]),
    deletePoll: vi.fn().mockResolvedValue(undefined),
    qrUrl: (id: string) => `/api/admin/polls/${id}/qr.png`,
    ...overrides
  };
  return base as unknown as AdminApiClient;
}

function poll(over: Partial<Poll> = {}): Poll {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    title: "Quickstart demo",
    slug: "quickstart-demo",
    status: "DRAFT",
    publicUrl: "http://localhost:8080/quickstart-demo",
    activeQuestionId: null,
    ...over
  };
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/login", name: "login", component: { template: "<div/>" } },
      { path: "/polls", name: "polls", component: { template: "<div/>" } },
      { path: "/polls/new", name: "poll-new", component: { template: "<div/>" } },
      { path: "/polls/:pollId", name: "poll-edit", component: { template: "<div/>" } }
    ]
  });
}

async function mountPage(client: AdminApiClient) {
  const router = makeRouter();
  await router.push("/polls");
  await router.isReady();
  return mount(PollListPage, {
    global: { plugins: [router] },
    props: { apiClient: client }
  });
}

describe("PollListPage", () => {
  it("shows a loading indicator while polls are being fetched", async () => {
    let resolve!: (polls: Poll[]) => void;
    const client = makeFake({
      listPolls: vi.fn(() => new Promise<Poll[]>((r) => (resolve = r)))
    });
    const wrapper = await mountPage(client);

    expect(wrapper.find('[data-testid="poll-list-loading"]').exists()).toBe(true);

    resolve([]);
    await flushPromises();
    expect(wrapper.find('[data-testid="poll-list-loading"]').exists()).toBe(false);
  });

  it("renders one row per poll with title, slug, join link and QR preview", async () => {
    const polls: Poll[] = [
      poll({
        id: "p1",
        title: "Quickstart demo",
        slug: "quickstart-demo",
        publicUrl: "http://localhost:8080/quickstart-demo"
      }),
      poll({
        id: "p2",
        title: "Favourite IDE?",
        slug: "fav-ide",
        publicUrl: "http://localhost:8080/fav-ide",
        status: "OPEN",
        activeQuestionId: "q-active"
      })
    ];
    const client = makeFake({ listPolls: vi.fn().mockResolvedValue(polls) });
    const wrapper = await mountPage(client);
    await flushPromises();

    const rows = wrapper.findAll('[data-testid="poll-row"]');
    expect(rows).toHaveLength(2);

    const first = rows[0];
    expect(first.text()).toContain("Quickstart demo");
    expect(first.text()).toContain("quickstart-demo");
    const joinLink = first.find('[data-testid="poll-join-link"]');
    expect(joinLink.exists()).toBe(true);
    expect(joinLink.attributes("href")).toBe("http://localhost:8080/quickstart-demo");
    const qrImg = first.find('[data-testid="poll-qr-img"]');
    expect(qrImg.exists()).toBe(true);
    expect(qrImg.attributes("src")).toBe("/api/admin/polls/p1/qr.png");

    const second = rows[1];
    expect(second.text()).toContain("OPEN");
  });

  it("renders an empty-state message when the presenter has no polls", async () => {
    const client = makeFake({ listPolls: vi.fn().mockResolvedValue([]) });
    const wrapper = await mountPage(client);
    await flushPromises();

    expect(wrapper.find('[data-testid="poll-list-empty"]').exists()).toBe(true);
    expect(wrapper.findAll('[data-testid="poll-row"]')).toHaveLength(0);
  });

  it("renders an actionable error message when the API call fails", async () => {
    const client = makeFake({
      listPolls: vi
        .fn()
        .mockRejectedValue(
          new AdminApiError(
            401,
            { code: "AUTH_REQUIRED", message: "Authentication required" },
            "Authentication required"
          )
        )
    });
    const wrapper = await mountPage(client);
    await flushPromises();

    const error = wrapper.find('[data-testid="poll-list-error"]');
    expect(error.exists()).toBe(true);
    // Per Principle VI: surface the cause, not a generic "something went wrong".
    expect(error.text().toLowerCase()).toContain("authentication");
  });

  it("invokes the API client to delete a poll on confirmation and removes the row", async () => {
    const polls = [poll({ id: "p1" }), poll({ id: "p2", slug: "second", title: "Second" })];
    const deletePoll = vi.fn().mockResolvedValue(undefined);
    const client = makeFake({
      listPolls: vi.fn().mockResolvedValue(polls),
      deletePoll
    });
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    try {
      const wrapper = await mountPage(client);
      await flushPromises();

      const firstDelete = wrapper.find('[data-testid="poll-row"] [data-testid="poll-delete"]');
      await firstDelete.trigger("click");
      await flushPromises();

      expect(deletePoll).toHaveBeenCalledWith("p1");
      const rows = wrapper.findAll('[data-testid="poll-row"]');
      expect(rows).toHaveLength(1);
      expect(rows[0].text()).toContain("Second");
    } finally {
      confirmSpy.mockRestore();
    }
  });
});
