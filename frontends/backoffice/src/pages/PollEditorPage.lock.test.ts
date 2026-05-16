import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import type { PollDetail } from "@slidev-polls/shared";
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

function lockedDetail(): PollDetail {
  return {
    id: "p1",
    title: "Locked poll",
    slug: "locked",
    status: "OPEN",
    publicUrl: "http://localhost:8080/locked",
    activeQuestionId: null,
    questions: [
      {
        id: "q1",
        prompt: "old prompt",
        ordinal: 0,
        status: "ACTIVE",
        minSelections: 1,
        maxSelections: 1,
        voteCount: 5,
        options: [
          { id: "o1", label: "A", position: 0 },
          { id: "o2", label: "B", position: 1 },
          { id: "o3", label: "C", position: 2 }
        ]
      }
    ]
  };
}

function makeFake(detail: PollDetail): AdminApiClient {
  return {
    getPoll: vi.fn().mockResolvedValue(detail),
    createPoll: vi.fn(),
    updatePoll: vi.fn(async () => detail),
    deletePoll: vi.fn(),
    qrUrl: (id: string) => `/api/admin/polls/${id}/qr.png`
  } as unknown as AdminApiClient;
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/polls", name: "polls", component: { template: "<div/>" } },
      { path: "/polls/:pollId", name: "poll-edit", component: { template: "<div/>" } },
      {
        path: "/polls/:pollId/deck-tokens",
        name: "deck-tokens",
        component: { template: "<div/>" }
      }
    ]
  });
}

async function mountEdit(detail: PollDetail) {
  const client = makeFake(detail);
  const router = makeRouter();
  await router.push("/polls/p1");
  await router.isReady();
  const w = mount(PollEditorPage, {
    global: { plugins: [router] },
    props: { mode: "edit", pollId: "p1", apiClient: client }
  });
  await flushPromises();
  return w;
}

describe("PollEditorPage — voteCount lock", () => {
  it("disables destructive controls when voteCount > 0", async () => {
    const w = await mountEdit(lockedDetail());

    const modeMulti = w.get('[data-testid="question-0-mode-multi"]');
    expect(modeMulti.attributes("disabled")).toBeDefined();

    const modeSingle = w.get('[data-testid="question-0-mode-single"]');
    expect(modeSingle.attributes("disabled")).toBeDefined();

    // Option-delete (indexed) buttons should be disabled. Three options -> at
    // least the option-delete-0 anchor exists.
    const optDelete = w.get('[data-testid="question-0-option-delete-0"]');
    expect(optDelete.attributes("disabled")).toBeDefined();

    // We mounted a single-question poll, so the question-0-delete control
    // isn't rendered (questions.length > 1 gate). Add a second question via
    // the editor first — but that requires the lock not to bleed onto add.
    // Instead, assert with a second-question fixture inline.
    const w2 = await mountEdit({
      ...lockedDetail(),
      questions: [
        ...lockedDetail().questions,
        {
          id: "q2",
          prompt: "another",
          ordinal: 1,
          status: "DRAFT",
          minSelections: 1,
          maxSelections: 1,
          voteCount: 5,
          options: [
            { id: "o4", label: "X", position: 0 },
            { id: "o5", label: "Y", position: 1 }
          ]
        }
      ]
    });
    const qDelete = w2.get('[data-testid="question-0-delete"]');
    expect(qDelete.attributes("disabled")).toBeDefined();
  });

  it("allows label / prompt edits when voteCount > 0", async () => {
    const w = await mountEdit(lockedDetail());

    const prompt = w.get<HTMLInputElement>('input[data-testid="question-prompt"]');
    expect(prompt.attributes("disabled")).toBeUndefined();
    await prompt.setValue("new prompt");
    expect(prompt.element.value).toBe("new prompt");

    const labels = w.findAll<HTMLInputElement>('input[data-testid="option-label"]');
    expect(labels.length).toBeGreaterThan(0);
    for (const label of labels) {
      expect(label.attributes("disabled")).toBeUndefined();
    }
    await labels[0].setValue("renamed");
    expect(labels[0].element.value).toBe("renamed");
  });
});
