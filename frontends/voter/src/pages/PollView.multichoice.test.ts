import { beforeEach, describe, it, expect, vi } from "vitest";

vi.mock("@slidev-polls/shared", async () => {
  const actual =
    await vi.importActual<typeof import("@slidev-polls/shared")>("@slidev-polls/shared");
  return { ...actual, openPollStream: vi.fn(() => () => {}) };
});

import { mount, flushPromises } from "@vue/test-utils";
import PollView from "./PollView.vue";
import type { PublicPollView } from "@slidev-polls/shared";

function multiView(min: number, max: number): PublicPollView {
  return {
    pollId: "p",
    slug: "s",
    title: "demo",
    state: "ACTIVE",
    activeQuestion: {
      id: "q1",
      prompt: "pick",
      ordinal: 1,
      status: "ACTIVE",
      minSelections: min,
      maxSelections: max,
      voteCount: 0,
      options: [
        { id: "a", label: "A", position: 0 },
        { id: "b", label: "B", position: 1 },
        { id: "c", label: "C", position: 2 }
      ]
    }
  };
}

describe("PollView – multiple choice", () => {
  beforeEach(() => {
    for (let i = window.localStorage.length - 1; i >= 0; i--) {
      const key = window.localStorage.key(i);
      if (key) window.localStorage.removeItem(key);
    }
  });

  // Multichoice (min=1, max=3) lets the voter check several options. The submit
  // payload uses the canonical optionIds[] shape; the server validates arity.
  it("submits optionIds[] for selected checkboxes", async () => {
    const submitVote = vi.fn().mockResolvedValue({ voteId: "v1", recordedAt: "now" });
    const client = {
      publicPoll: vi.fn().mockResolvedValue(multiView(1, 3)),
      submitVote,
      retractVote: vi.fn()
    };
    const w = mount(PollView, { props: { slug: "s", apiClient: client as never } });
    await flushPromises();

    await w.get('[data-testid="option-a"]').trigger("click");
    await w.get('[data-testid="option-c"]').trigger("click");
    await w.get('[data-testid="poll-submit"]').trigger("click");
    await flushPromises();

    expect(submitVote).toHaveBeenCalledWith("s", { optionIds: ["a", "c"] });
  });

  // Cap-disable: once max is reached, the remaining unchecked options must
  // be visibly disabled so the voter can see the ceiling. Already-checked
  // ones stay clickable so they can deselect to swap.
  it("disables unchecked options at max", async () => {
    const client = {
      publicPoll: vi.fn().mockResolvedValue(multiView(1, 2)),
      submitVote: vi.fn(),
      retractVote: vi.fn()
    };
    const w = mount(PollView, { props: { slug: "s", apiClient: client as never } });
    await flushPromises();

    await w.get('[data-testid="option-a"]').trigger("click");
    await w.get('[data-testid="option-b"]').trigger("click");

    expect(w.get('[data-testid="option-c"]').attributes("disabled")).toBeDefined();
    expect(w.get('[data-testid="option-a"]').attributes("disabled")).toBeUndefined();
  });
});
