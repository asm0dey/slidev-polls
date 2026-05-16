import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import type { CreatePollRequest, PollDetail } from "@slidev-polls/shared";
import PollEditorPage from "./PollEditorPage.vue";
import type { AdminApiClient } from "../lib/admin-api";
import { AdminApiError } from "../lib/admin-api";

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
    questions: [
      {
        id: "q1",
        prompt: "Which JVM?",
        ordinal: 0,
        status: "DRAFT",
        minSelections: 1,
        maxSelections: 1,
        voteCount: 0,
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

  it("submits the form and navigates to the edit page (not /polls) on success", async () => {
    const createPoll = vi.fn(async (req: CreatePollRequest) => pollDetail({ title: req.title }));
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
    // After create, router.replace to the edit page — NOT /polls
    expect(router.currentRoute.value.name).toBe("poll-edit");
    expect(router.currentRoute.value.params.pollId).toBe("p1");
  });

  it("explains why Create is disabled in create mode", async () => {
    const { wrapper } = await mountCreate(makeFake());
    expect(wrapper.get('[data-testid="poll-editor-submit"]').attributes("disabled")).toBeDefined();
    expect(wrapper.find('[data-testid="poll-submit-hint"]').text()).toContain("Add a title");

    await wrapper.get('input[data-testid="poll-title"]').setValue("My talk");
    expect(wrapper.find('[data-testid="poll-submit-hint"]').text()).toContain(
      "Add a question prompt"
    );
  });

  it("surfaces server-issued SLUG_TAKEN as a readable error, not a stack trace", async () => {
    const createPoll = vi
      .fn()
      .mockRejectedValue(
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
    const getPoll = vi
      .fn()
      .mockResolvedValue(pollDetail({ allowedOrigins: ["http://a.example", "http://b.example"] }));
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

  describe("copy snippet", () => {
    let writeText: ReturnType<typeof vi.fn>;

    beforeEach(() => {
      writeText = vi.fn().mockResolvedValue(undefined);
      Object.defineProperty(navigator, "clipboard", {
        value: { writeText },
        configurable: true
      });
    });

    it("copiesSnippetWithSlugPollIdAndQuestionIdNoDeckToken", async () => {
      const client = makeFake({});
      const { wrapper } = await mountEdit(client);

      await wrapper.find('[data-testid="question-copy-snippet"]').trigger("click");
      await flushPromises();

      // Deck token must NOT be in the markup — PollPanel reads it from the
      // in-deck auth control and warns in dev if a deckToken prop is present.
      expect(writeText).toHaveBeenCalledTimes(1);
      const snippet = writeText.mock.calls[0][0] as string;
      expect(snippet).toContain('slug="quickstart-demo"');
      expect(snippet).toContain('pollId="p1"');
      expect(snippet).toContain('questionId="q1"');
      expect(snippet).not.toContain("deckToken");
      expect(snippet).toContain("<PollResults");
    });

    it("showsCopiedConfirmationAfterSuccess", async () => {
      const client = makeFake({});
      const { wrapper } = await mountEdit(client);

      await wrapper.find('[data-testid="question-copy-snippet"]').trigger("click");
      await flushPromises();

      expect(wrapper.find('[data-testid="question-copy-snippet-confirm"]').exists()).toBe(true);
    });

    it("surfacesClipboardErrorInFormError", async () => {
      writeText.mockRejectedValueOnce(new Error("clipboard blocked"));
      const client = makeFake({});
      const { wrapper } = await mountEdit(client);

      await wrapper.find('[data-testid="question-copy-snippet"]').trigger("click");
      await flushPromises();

      const error = wrapper.find('[data-testid="poll-form-error"]');
      expect(error.exists()).toBe(true);
    });
  });

  it("deletes the poll on confirmation and navigates back to /polls", async () => {
    const deletePoll = vi.fn().mockResolvedValue(undefined);
    const client = makeFake({ deletePoll });
    const { wrapper, router } = await mountEdit(client);

    await wrapper.get('[data-testid="menu-trigger"]').trigger("click");
    await wrapper.get('[data-testid="menu-item-delete"]').trigger("click");
    await flushPromises();

    // Typed-slug confirmation required — the delete dialog is the second
    // ConfirmDialog rendered after clear-votes (which stays mounted, closed).
    const typed = wrapper.findAll('[data-testid="confirm-dialog-typed"]');
    expect(typed).toHaveLength(1);
    await typed[0].setValue("quickstart-demo");

    const confirmButtons = wrapper.findAll('[data-testid="confirm-dialog-confirm"]');
    // [0] is clear-votes (closed), [1] is delete (open with typed input)
    await confirmButtons[confirmButtons.length - 1].trigger("click");
    await flushPromises();

    expect(deletePoll).toHaveBeenCalledWith("p1");
    expect(router.currentRoute.value.path).toBe("/polls");
  });

  it("Delete poll opens ConfirmDialog (no native confirm) and disables until slug is typed", async () => {
    const getPoll = vi.fn().mockResolvedValue(
      pollDetail({
        id: "p1",
        title: "Workshop survey",
        slug: "workshop",
        questions: []
      })
    );
    const client = makeFake({ getPoll });
    const { wrapper } = await mountEdit(client);

    await wrapper.get('[data-testid="menu-trigger"]').trigger("click");
    await wrapper.get('[data-testid="menu-item-delete"]').trigger("click");
    // The delete dialog is the one with the typed-confirm input.
    const typedInputs = wrapper.findAll('[data-testid="confirm-dialog-typed"]');
    expect(typedInputs).toHaveLength(1);
    const dlg = typedInputs[0].element.closest('[data-testid="confirm-dialog"]') as HTMLElement;
    expect(dlg).not.toBeNull();
    const confirmBtn = dlg.querySelector(
      '[data-testid="confirm-dialog-confirm"]'
    ) as HTMLButtonElement;
    expect(confirmBtn.hasAttribute("disabled")).toBe(true);

    await typedInputs[0].setValue("workshop");
    expect(confirmBtn.hasAttribute("disabled")).toBe(false);
  });

  it("does not redirect to /polls after saving an edit", async () => {
    const updatePoll = vi.fn(async (_id: string, _req) => pollDetail());
    const client = makeFake({ updatePoll });
    const { wrapper, router } = await mountEdit(client);

    await wrapper.find('[data-testid="poll-editor-submit"]').trigger("click");
    await flushPromises();

    expect(updatePoll).toHaveBeenCalledTimes(1);
    // Must stay on the edit page, not redirect away
    expect(router.currentRoute.value.path).toBe("/polls/p1");
  });

  it("sends question and option ids in PATCH payload (so backend preserves them)", async () => {
    const updatePoll = vi.fn(async (_pollId: string, _body: unknown) => pollDetail());
    const client = makeFake({ updatePoll });
    const { wrapper } = await mountEdit(client);

    // Edit the prompt so the form is dirty but keep options intact
    await wrapper.find('input[data-testid="question-prompt"]').setValue("edited");

    await wrapper.find('[data-testid="poll-editor-submit"]').trigger("click");
    await flushPromises();

    expect(updatePoll).toHaveBeenCalledWith(
      "p1",
      expect.objectContaining({
        questions: [
          expect.objectContaining({
            id: "q1",
            prompt: "edited",
            options: [
              expect.objectContaining({ id: "o1", label: "OpenJDK" }),
              expect.objectContaining({ id: "o2", label: "GraalVM" })
            ]
          })
        ]
      })
    );
  });

  it("opens the confirm dialog when Clear votes is clicked", async () => {
    const clearVotes = vi.fn().mockResolvedValue(pollDetail());
    const client = makeFake({ clearVotes });
    const { wrapper } = await mountEdit(client);

    await wrapper.get('[data-testid="menu-trigger"]').trigger("click");
    await wrapper.get('[data-testid="menu-item-clear-votes"]').trigger("click");
    await flushPromises();

    const dialog = wrapper.find('[data-testid="confirm-dialog"]').element as HTMLDialogElement;
    expect(dialog.open || dialog.hasAttribute("open")).toBe(true);
    expect(clearVotes).not.toHaveBeenCalled();
  });

  it("calls clearVotes and reloads the poll when the modal confirm fires", async () => {
    const clearedDetail = pollDetail({
      activeQuestionId: null,
      questions: [{ ...pollDetail().questions[0], status: "DRAFT" }]
    });
    const clearVotes = vi.fn().mockResolvedValue(clearedDetail);
    const client = makeFake({ clearVotes });
    const { wrapper } = await mountEdit(client);

    // Open the dialog
    await wrapper.get('[data-testid="menu-trigger"]').trigger("click");
    await wrapper.get('[data-testid="menu-item-clear-votes"]').trigger("click");
    await flushPromises();

    // Click the confirm button inside the dialog
    await wrapper.find('[data-testid="confirm-dialog-confirm"]').trigger("click");
    await flushPromises();

    expect(clearVotes).toHaveBeenCalledWith("p1");

    // Dialog should now be closed
    const dialog = wrapper.find('[data-testid="confirm-dialog"]').element as HTMLDialogElement;
    expect(dialog.open || dialog.hasAttribute("open")).toBe(false);
  });

  it("hides Copy snippet while there are unsaved edits and re-shows after save", async () => {
    const updatePoll = vi.fn(async (_id: string, _req: unknown) => pollDetail());
    const client = makeFake({ updatePoll });
    const { wrapper } = await mountEdit(client);

    // Initially clean — Copy snippet button should be visible, hint absent
    expect(wrapper.find('[data-testid="question-copy-snippet"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="copy-snippet-disabled-hint"]').exists()).toBe(false);

    // Make a dirty edit
    await wrapper.find('input[data-testid="question-prompt"]').setValue("changed prompt");

    // Snippet button should now be hidden; hint should appear
    expect(wrapper.find('[data-testid="question-copy-snippet"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="copy-snippet-disabled-hint"]').exists()).toBe(true);

    // Save — update returns the original detail (same data, so becomes clean again)
    await wrapper.find('[data-testid="poll-editor-submit"]').trigger("click");
    await flushPromises();

    // After save snippet button should be back, hint gone
    expect(wrapper.find('[data-testid="question-copy-snippet"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="copy-snippet-disabled-hint"]').exists()).toBe(false);
  });

  it("hides Clear votes and Delete behind an overflow menu", async () => {
    const getPoll = vi.fn().mockResolvedValue(
      pollDetail({
        id: "p1",
        title: "Workshop",
        slug: "workshop",
        questions: []
      })
    );
    const client = makeFake({ getPoll });
    const { wrapper } = await mountEdit(client);

    expect(wrapper.find('[data-testid="poll-clear-votes"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="poll-delete"]').exists()).toBe(false);

    await wrapper.get('[data-testid="menu-trigger"]').trigger("click");
    expect(wrapper.find('[data-testid="menu-item-clear-votes"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="menu-item-delete"]').exists()).toBe(true);
  });
});
