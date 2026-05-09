import { describe, it, expect, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import PollPanel from "./PollPanel.vue";

vi.mock("@polls/shared", async () => {
  const actual = await vi.importActual<typeof import("@polls/shared")>("@polls/shared");
  return {
    ...actual,
    openPollStream: (
      _base: string,
      _slug: string,
      handlers: {
        onSnapshot: (e: unknown) => void;
        onTally: (e: unknown) => void;
        onQuestionClosed: (e: unknown) => void;
        onConnectionStateChange: (s: string) => void;
      }
    ) => {
      handlers.onSnapshot({
        pollId: "p1",
        slug: "s",
        emittedAt: "now",
        activeQuestion: {
          id: "q1",
          prompt: "Pick",
          ordinal: 1,
          options: [
            { id: "a", label: "React", position: 0 },
            { id: "b", label: "Vue", position: 1 }
          ]
        },
        tally: [
          { optionId: "a", count: 1 },
          { optionId: "b", count: 3 }
        ]
      });
      return () => {};
    }
  };
});

vi.mock("../composables/useDeckAuth", () => ({
  useDeckAuth: () => ({
    status: { value: "signed-out" },
    state: { value: { token: null } },
    markRevoked: vi.fn()
  })
}));

describe("PollPanel", () => {
  it("renders ResultsPanel from snapshot", async () => {
    const w = mount(PollPanel, { props: { slug: "s" } });
    await flushPromises();
    expect(w.find("[data-testid='results-panel']").exists()).toBe(true);
    expect(w.text()).toContain("Pick");
    expect(w.text()).toContain("4 votes");
  });
});
