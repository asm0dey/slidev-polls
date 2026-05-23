import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import type { CreatePollRequest, PollDetail } from "@slidev-polls/shared";
import PollEditorPage from "./PollEditorPage.vue";
import type { AdminApiClient } from "../lib/admin-api";

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

function pollDetail(over: Partial<PollDetail> = {}): PollDetail {
  return {
    id: "p1",
    title: "Created",
    slug: "created",
    status: "DRAFT",
    publicUrl: "http://localhost:8080/created",
    activeQuestionId: null,
    isOwner: true,
    questions: [
      {
        id: "q1",
        prompt: "x",
        ordinal: 0,
        status: "DRAFT",
        minSelections: 1,
        maxSelections: 1,
        voteCount: 0,
        options: [
          { id: "o1", label: "A", position: 0 },
          { id: "o2", label: "B", position: 1 }
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
    listCollaborators: vi.fn().mockResolvedValue([]),
    addCollaborator: vi.fn().mockResolvedValue(undefined),
    removeCollaborator: vi.fn().mockResolvedValue(undefined),
    transferPoll: vi.fn().mockResolvedValue(undefined),
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
  return mount(PollEditorPage, {
    global: { plugins: [router] },
    props: { mode: "create", apiClient: client }
  });
}

describe("PollEditorPage — arity toggle", () => {
  it("Pick many reveals min/max inputs and posts arity", async () => {
    const createPoll = vi.fn(async (req: CreatePollRequest) => pollDetail({ title: req.title }));
    const client = makeFake({ createPoll });
    const w = await mountCreate(client);

    await w.find('input[data-testid="poll-title"]').setValue("Multi");
    await w.find('input[data-testid="question-prompt"]').setValue("multi?");

    // before flipping, min/max inputs should not exist
    expect(w.find('[data-testid="question-0-min"]').exists()).toBe(false);
    expect(w.find('[data-testid="question-0-max"]').exists()).toBe(false);

    // flip to Pick many
    await w.get('[data-testid="question-0-mode-multi"]').setValue(true);
    // now min/max inputs appear with the (1,3) starting pair
    const minInput = w.find<HTMLInputElement>('[data-testid="question-0-min"]');
    const maxInput = w.find<HTMLInputElement>('[data-testid="question-0-max"]');
    expect(minInput.exists()).toBe(true);
    expect(maxInput.exists()).toBe(true);

    await minInput.setValue("0");
    await maxInput.setValue("3");

    const opts = w.findAll('input[data-testid="option-label"]');
    await opts[0].setValue("A");
    await opts[1].setValue("B");

    await w.find('[data-testid="poll-editor-submit"]').trigger("click");
    await flushPromises();

    expect(createPoll).toHaveBeenCalledTimes(1);
    const sent = createPoll.mock.calls[0][0] as CreatePollRequest;
    expect(sent.questions).toHaveLength(1);
    expect(sent.questions[0]).toMatchObject({
      prompt: "multi?",
      minSelections: 0,
      maxSelections: 3
    });
  });

  it("Pick one keeps (1,1) defaults", async () => {
    const createPoll = vi.fn(async (req: CreatePollRequest) => pollDetail({ title: req.title }));
    const client = makeFake({ createPoll });
    const w = await mountCreate(client);

    await w.find('input[data-testid="poll-title"]').setValue("Single");
    await w.find('input[data-testid="question-prompt"]').setValue("which?");
    const opts = w.findAll('input[data-testid="option-label"]');
    await opts[0].setValue("A");
    await opts[1].setValue("B");

    // The "Pick one" radio is checked by default — assert it
    const single = w.get<HTMLInputElement>('[data-testid="question-0-mode-single"]');
    expect((single.element as HTMLInputElement).checked).toBe(true);
    expect(w.find('[data-testid="question-0-min"]').exists()).toBe(false);
    expect(w.find('[data-testid="question-0-max"]').exists()).toBe(false);

    await w.find('[data-testid="poll-editor-submit"]').trigger("click");
    await flushPromises();

    const sent = createPoll.mock.calls[0][0] as CreatePollRequest;
    expect(sent.questions[0]).toMatchObject({ minSelections: 1, maxSelections: 1 });
  });
});
