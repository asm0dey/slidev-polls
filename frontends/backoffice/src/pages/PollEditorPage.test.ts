import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import type { CreatePollRequest, DeckTokenMinted, PollDetail } from "@polls/shared";
import PollEditorPage from "./PollEditorPage.vue";
import type { AdminApiClient } from "../lib/admin-api";
import { AdminApiError } from "../lib/admin-api";

// Backs the editor side of @TS-002 (create poll w/ questions), @TS-003
// (activate atomically closes any prior ACTIVE question), @TS-005 (close
// active question), @TS-006 (delete poll), and the @TS-013 "slug taken"
// surface (server-issued SLUG_TAKEN must reach the presenter as a
// readable message, not a stack trace).

function pollDetail(over: Partial<PollDetail> = {}): PollDetail {
  return {
    id: "p1",
    title: "Quickstart demo",
    slug: "quickstart-demo",
    status: "DRAFT",
    publicUrl: "http://localhost:8080/quickstart-demo",
    activeQuestionId: null,
    style: {},
    questions: [
      {
        id: "q1",
        prompt: "Which JVM?",
        ordinal: 0,
        status: "DRAFT",
        options: [
          { id: "o1", label: "OpenJDK", position: 0 },
          { id: "o2", label: "GraalVM", position: 1 }
        ]
      }
    ],
    ...over
  };
}

function makeFake(overrides: Partial<AdminApiClient> = {}): AdminApiClient {
  return {
    getPoll: vi.fn().mockResolvedValue(pollDetail()),
    createPoll: vi.fn(async (req: CreatePollRequest) => pollDetail({ title: req.title })),
    updatePoll: vi.fn(async (_id: string, _req) => pollDetail()),
    deletePoll: vi.fn().mockResolvedValue(undefined),
    activateQuestion: vi.fn(async () =>
      pollDetail({
        activeQuestionId: "q1",
        questions: [{ ...pollDetail().questions[0], status: "ACTIVE" }]
      })
    ),
    closeActiveQuestion: vi.fn(async () => pollDetail({ activeQuestionId: null })),
    qrUrl: (id: string) => `/api/admin/polls/${id}/qr.png`,
    ...overrides
  } as unknown as AdminApiClient;
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/polls", name: "polls", component: { template: "<div/>" } },
      { path: "/polls/new", name: "poll-new", component: { template: "<div/>" } },
      { path: "/polls/:pollId", name: "poll-edit", component: { template: "<div/>" } },
      {
        path: "/polls/:pollId/deck-tokens",
        name: "deck-tokens",
        component: { template: "<div/>" }
      }
    ]
  });
}

async function mountCreate(client: AdminApiClient) {
  const router = makeRouter();
  await router.push("/polls/new");
  await router.isReady();
  return {
    router,
    wrapper: mount(PollEditorPage, {
      global: { plugins: [router] },
      props: { mode: "create", apiClient: client }
    })
  };
}

async function mountEdit(client: AdminApiClient, pollId = "p1") {
  const router = makeRouter();
  await router.push(`/polls/${pollId}`);
  await router.isReady();
  const wrapper = mount(PollEditorPage, {
    global: { plugins: [router] },
    props: { mode: "edit", pollId, apiClient: client }
  });
  await flushPromises();
  return { router, wrapper };
}

describe("PollEditorPage — create mode", () => {
  it("starts with one empty question that has two empty options", async () => {
    const { wrapper } = await mountCreate(makeFake());
    expect(wrapper.findAll('[data-testid="question-block"]')).toHaveLength(1);
    expect(wrapper.findAll('[data-testid="option-row"]')).toHaveLength(2);
  });

  it("submits the form and redirects to /polls on success", async () => {
    const createPoll = vi.fn(async (req: CreatePollRequest) =>
      pollDetail({ title: req.title })
    );
    const client = makeFake({ createPoll });
    const { wrapper, router } = await mountCreate(client);

    await wrapper.find('input[data-testid="poll-title"]').setValue("Quickstart demo");
    const optionInputs = wrapper.findAll('input[data-testid="option-label"]');
    await optionInputs[0].setValue("OpenJDK");
    await optionInputs[1].setValue("GraalVM");
    await wrapper.find('input[data-testid="question-prompt"]').setValue("Which JVM?");

    await wrapper.find('[data-testid="poll-editor-submit"]').trigger("click");
    await flushPromises();

    expect(createPoll).toHaveBeenCalledTimes(1);
    const sent = createPoll.mock.calls[0][0] as CreatePollRequest;
    expect(sent.title).toBe("Quickstart demo");
    expect(sent.questions).toHaveLength(1);
    expect(sent.questions[0].prompt).toBe("Which JVM?");
    expect(sent.questions[0].options.map((o) => o.label)).toEqual(["OpenJDK", "GraalVM"]);
    expect(router.currentRoute.value.path).toBe("/polls");
  });

  it("surfaces server-issued SLUG_TAKEN as a readable error, not a stack trace", async () => {
    const createPoll = vi.fn().mockRejectedValue(
      new AdminApiError(
        409,
        { code: "SLUG_TAKEN", message: "Slug already in use" },
        "Slug already in use"
      )
    );
    const client = makeFake({ createPoll });
    const { wrapper } = await mountCreate(client);

    await wrapper.find('input[data-testid="poll-title"]').setValue("My talk");
    await wrapper.find('input[data-testid="question-prompt"]').setValue("Q?");
    const opts = wrapper.findAll('input[data-testid="option-label"]');
    await opts[0].setValue("A");
    await opts[1].setValue("B");
    await wrapper.find('[data-testid="poll-editor-submit"]').trigger("click");
    await flushPromises();

    const error = wrapper.find('[data-testid="poll-form-error"]');
    expect(error.exists()).toBe(true);
    expect(error.text().toLowerCase()).toContain("slug");
  });
});

describe("PollEditorPage — allowed origins editor", () => {
  it("populates AllowedOriginsField from PollDetail.allowedOrigins on load", async () => {
    const getPoll = vi.fn().mockResolvedValue(
      pollDetail({ allowedOrigins: ["http://a.example", "http://b.example"] })
    );
    const client = makeFake({ getPoll });
    const { wrapper } = await mountEdit(client);

    const chips = wrapper.findAll("[data-testid='origin-chip']");
    expect(chips).toHaveLength(2);
  });

  it("adds origins via chip-list input and sends them on save", async () => {
    const getPoll = vi.fn().mockResolvedValue(pollDetail({ allowedOrigins: [] }));
    const updatePoll = vi.fn(async (_id: string, _req) => pollDetail());
    const client = makeFake({ getPoll, updatePoll });
    const { wrapper } = await mountEdit(client);

    const input = wrapper.find<HTMLInputElement>("input.sp-aof-input");
    await input.setValue("http://a.example");
    await input.trigger("keydown", { key: "Enter" });
    await input.setValue("http://b.example");
    await input.trigger("keydown", { key: "Enter" });

    await wrapper.find('[data-testid="poll-editor-submit"]').trigger("click");
    await flushPromises();

    const sentReq = (updatePoll.mock.calls[0] as unknown[])[1] as { allowedOrigins: string[] };
    expect(sentReq.allowedOrigins).toEqual(["http://a.example", "http://b.example"]);
  });

  it("displays pre-loaded origins as chips", async () => {
    const origins = ["http://a.example", "http://b.example"];
    const getPoll = vi.fn().mockResolvedValue(pollDetail({ allowedOrigins: origins }));
    const client = makeFake({ getPoll });
    const { wrapper } = await mountEdit(client);

    const chips = wrapper.findAll("[data-testid='origin-chip']");
    expect(chips).toHaveLength(origins.length);
  });
});

describe("PollEditorPage — edit mode", () => {
  it("loads the poll on mount and populates fields", async () => {
    const getPoll = vi.fn().mockResolvedValue(pollDetail());
    const client = makeFake({ getPoll });
    const { wrapper } = await mountEdit(client);

    expect(getPoll).toHaveBeenCalledWith("p1");
    const title = wrapper.find<HTMLInputElement>('input[data-testid="poll-title"]');
    expect(title.element.value).toBe("Quickstart demo");
    const slug = wrapper.find<HTMLInputElement>('input[data-testid="slug-input"]');
    expect(slug.element.value).toBe("quickstart-demo");
  });

  it("activates a draft question when the Activate button is clicked", async () => {
    const activateQuestion = vi.fn(async () =>
      pollDetail({
        activeQuestionId: "q1",
        questions: [{ ...pollDetail().questions[0], status: "ACTIVE" }]
      })
    );
    const client = makeFake({ activateQuestion });
    const { wrapper } = await mountEdit(client);

    await wrapper.find('[data-testid="question-activate"]').trigger("click");
    await flushPromises();

    expect(activateQuestion).toHaveBeenCalledWith("p1", { questionId: "q1" });
  });

  it("closes the active question via the Close button", async () => {
    const closeActiveQuestion = vi.fn(async () => pollDetail({ activeQuestionId: null }));
    const getPoll = vi.fn().mockResolvedValue(
      pollDetail({
        activeQuestionId: "q1",
        questions: [{ ...pollDetail().questions[0], status: "ACTIVE" }]
      })
    );
    const client = makeFake({ getPoll, closeActiveQuestion });
    const { wrapper } = await mountEdit(client);

    await wrapper.find('[data-testid="question-close"]').trigger("click");
    await flushPromises();
    expect(closeActiveQuestion).toHaveBeenCalledWith("p1");
  });

  describe("copy snippet", () => {
    let writeText: ReturnType<typeof vi.fn>;

    beforeEach(() => {
      writeText = vi.fn().mockResolvedValue(undefined);
      Object.defineProperty(navigator, "clipboard", {
        value: { writeText },
        configurable: true
      });
    });

    function mintedToken(over: Partial<DeckTokenMinted> = {}): DeckTokenMinted {
      return {
        id: "tok-id",
        pollId: "p1",
        label: "snippet for Which JVM?",
        plaintext: "plain-tok-123",
        createdAt: "2025-01-01T00:00:00Z",
        revokedAt: null,
        ...over
      };
    }

    it("mintsTokenAndCopiesSnippetWithAllFourValues", async () => {
      const mintDeckToken = vi.fn().mockResolvedValue(mintedToken());
      const client = makeFake({ mintDeckToken });
      const { wrapper } = await mountEdit(client);

      await wrapper.find('[data-testid="question-copy-snippet"]').trigger("click");
      await flushPromises();

      expect(mintDeckToken).toHaveBeenCalledTimes(1);
      expect(mintDeckToken.mock.calls[0][0]).toBe("p1");
      expect(writeText).toHaveBeenCalledTimes(1);
      const snippet = writeText.mock.calls[0][0] as string;
      expect(snippet).toContain('slug="quickstart-demo"');
      expect(snippet).toContain('pollId="p1"');
      expect(snippet).toContain('questionId="q1"');
      expect(snippet).toContain('deckToken="plain-tok-123"');
      expect(snippet).toContain("<PollResults");
    });

    it("showsCopiedConfirmationAfterSuccess", async () => {
      const mintDeckToken = vi.fn().mockResolvedValue(mintedToken());
      const client = makeFake({ mintDeckToken });
      const { wrapper } = await mountEdit(client);

      await wrapper.find('[data-testid="question-copy-snippet"]').trigger("click");
      await flushPromises();

      expect(wrapper.find('[data-testid="question-copy-snippet-confirm"]').exists()).toBe(true);
    });

    it("surfacesMintErrorInFormError", async () => {
      const mintDeckToken = vi.fn().mockRejectedValue(
        new AdminApiError(
          403,
          { code: "FORBIDDEN", message: "Forbidden" },
          "Forbidden"
        )
      );
      const client = makeFake({ mintDeckToken });
      const { wrapper } = await mountEdit(client);

      await wrapper.find('[data-testid="question-copy-snippet"]').trigger("click");
      await flushPromises();

      expect(writeText).not.toHaveBeenCalled();
      const error = wrapper.find('[data-testid="poll-form-error"]');
      expect(error.exists()).toBe(true);
    });
  });

  it("deletes the poll on confirmation and navigates back to /polls", async () => {
    const deletePoll = vi.fn().mockResolvedValue(undefined);
    const client = makeFake({ deletePoll });
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    try {
      const { wrapper, router } = await mountEdit(client);
      await wrapper.find('[data-testid="poll-delete"]').trigger("click");
      await flushPromises();

      expect(deletePoll).toHaveBeenCalledWith("p1");
      expect(router.currentRoute.value.path).toBe("/polls");
    } finally {
      confirmSpy.mockRestore();
    }
  });
});
