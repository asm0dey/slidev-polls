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

describe("PollView – abstain (min=0)", () => {
  beforeEach(() => {
    for (let i = window.localStorage.length - 1; i >= 0; i--) {
      const key = window.localStorage.key(i);
      if (key) window.localStorage.removeItem(key);
    }
  });

  // Abstain semantics: when the active question accepts zero selections
  // (min=0), the submit button reads "Skip" until something is checked.
  // This signals to the voter that "no answer" is a real, accepted choice.
  it("submit label is 'Skip' when nothing checked", async () => {
    const client = {
      publicPoll: vi.fn().mockResolvedValue(multiView(0, 3)),
      submitVote: vi.fn().mockResolvedValue({ voteId: "v1", recordedAt: "now" }),
      retractVote: vi.fn()
    };
    const w = mount(PollView, { props: { slug: "s", apiClient: client as never } });
    await flushPromises();

    expect(w.get('[data-testid="poll-submit"]').text()).toBe("Skip");
    await w.get('[data-testid="option-a"]').trigger("click");
    expect(w.get('[data-testid="poll-submit"]').text()).toBe("Submit answer");
  });

  // Skip path: pressing Skip posts an empty ballot. The server treats an
  // empty optionIds[] as a recorded "abstain" vote so the voter can not
  // re-submit and the presenter sees the participation count.
  it("posts an empty ballot when min=0 and Skip is pressed", async () => {
    const submitVote = vi.fn().mockResolvedValue({ voteId: "v1", recordedAt: "now" });
    const client = {
      publicPoll: vi.fn().mockResolvedValue(multiView(0, 3)),
      submitVote,
      retractVote: vi.fn()
    };
    const w = mount(PollView, { props: { slug: "s", apiClient: client as never } });
    await flushPromises();

    await w.get('[data-testid="poll-submit"]').trigger("click");
    await flushPromises();

    expect(submitVote).toHaveBeenCalledWith("s", { optionIds: [] });
  });
});
