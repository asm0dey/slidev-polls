import { describe, it, expect, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import PollPanel from "./PollPanel.vue";

vi.mock("@slidev-polls/shared", async () => {
  const actual =
    await vi.importActual<typeof import("@slidev-polls/shared")>("@slidev-polls/shared");
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

const authState = vi.hoisted(() => ({
  value: "anonymous" as "anonymous" | "signed-in"
}));
vi.mock("../composables/useDeckAuth", () => ({
  useDeckAuth: () => ({
    status: {
      get value() {
        return authState.value;
      }
    },
    state: {
      value: { token: authState.value === "signed-in" ? "tok" : null }
    },
    markRevoked: vi.fn()
  })
}));

vi.mock("qr-code-styling", () => {
  class FakeQRCodeStyling {
    append() {}
    update() {}
  }
  return { default: FakeQRCodeStyling };
});

describe("PollPanel", () => {
  it("renders ResultsPanel from snapshot", async () => {
    authState.value = "anonymous";
    const w = mount(PollPanel, { props: { slug: "s" } });
    await flushPromises();
    expect(w.find("[data-testid='results-panel']").exists()).toBe(true);
    expect(w.text()).toContain("Pick");
    expect(w.text()).toContain("4 votes");
  });

  it("hides the QR button when not signed in", async () => {
    authState.value = "anonymous";
    const w = mount(PollPanel, { props: { slug: "s", server: "https://example.test" } });
    await flushPromises();
    expect(w.find("[data-testid='poll-qr-toggle']").exists()).toBe(false);
  });

  it("renders the QR button when signed in", async () => {
    authState.value = "signed-in";
    const w = mount(PollPanel, { props: { slug: "s", server: "https://example.test" } });
    await flushPromises();
    expect(w.find("[data-testid='poll-qr-toggle']").exists()).toBe(true);
  });
});
