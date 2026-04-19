import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import type { QuestionClosedEvent, SnapshotEvent, StreamHandlers, TallyDeltaEvent } from "@polls/shared";
import PollResults from "./PollResults.vue";

// Tests mirror the live-results scenarios:
//   @TS-030 — snapshot renders the active question with options and initial tallies
//   @TS-031 — an active-question-change snapshot rotates the UI
//   @TS-032 — a stray tally whose questionId does NOT match the current snapshot is ignored
//   @TS-033 — connection loss renders the "live updates paused" badge; component does not throw
//   @TS-034 — a later snapshot clears the paused indicator
//   (bonus) question-closed leaves the deck navigable with a soft waiting message

/**
 * Stub for {@code openPollStream}. We capture the handlers object so each test can synthesise
 * the events it cares about; the returned function records that the page unsubscribed.
 */
let capturedHandlers: StreamHandlers | null = null;
let unsubscribeCalls = 0;

vi.mock("@polls/shared", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@polls/shared")>();
  return {
    ...actual,
    openPollStream: (
      _baseUrl: string,
      _slug: string,
      handlers: StreamHandlers
    ): (() => void) => {
      capturedHandlers = handlers;
      return () => {
        unsubscribeCalls++;
      };
    }
  };
});

function snapshot(
  questionId: string,
  options: Array<{ id: string; label: string; position: number }>,
  counts: Record<string, number> = {}
): SnapshotEvent {
  return {
    pollId: "p-1",
    slug: "test",
    activeQuestion: {
      id: questionId,
      prompt: `Question ${questionId}`,
      ordinal: 1,
      options
    },
    tally: options.map((o) => ({ optionId: o.id, count: counts[o.id] ?? 0 })),
    emittedAt: new Date().toISOString()
  };
}

function mountResults(propsOverride: Record<string, unknown> = {}) {
  return mount(PollResults, {
    props: { slug: "my-talk", ...propsOverride }
  });
}

describe("PollResults", () => {
  beforeEach(() => {
    capturedHandlers = null;
    unsubscribeCalls = 0;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 200 })));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // @TS-030 — snapshot populates the question + options + initial tallies.
  it("renders the active question + options after the first snapshot", async () => {
    const wrapper = mountResults();
    await flushPromises();
    expect(capturedHandlers).not.toBeNull();
    capturedHandlers!.onSnapshot(
      snapshot("q-1", [
        { id: "opt-a", label: "OpenJDK", position: 0 },
        { id: "opt-b", label: "GraalVM", position: 1 }
      ])
    );
    await flushPromises();
    expect(wrapper.html()).toContain("Question q-1");
    expect(wrapper.get("[data-testid='poll-bar-opt-a']").text()).toContain("OpenJDK");
    expect(wrapper.get("[data-testid='poll-bar-opt-b']").text()).toContain("GraalVM");
  });

  // @TS-030 / @TS-031 — a tally event rotates the count for the matching option.
  it("updates a bar when a matching tally event arrives", async () => {
    const wrapper = mountResults();
    await flushPromises();
    capturedHandlers!.onSnapshot(
      snapshot("q-1", [
        { id: "opt-a", label: "OpenJDK", position: 0 },
        { id: "opt-b", label: "GraalVM", position: 1 }
      ])
    );
    await flushPromises();
    capturedHandlers!.onTally({
      pollId: "p-1",
      questionId: "q-1",
      optionId: "opt-a",
      count: 3,
      emittedAt: new Date().toISOString()
    } satisfies TallyDeltaEvent);
    await flushPromises();
    expect(wrapper.get("[data-testid='poll-bar-opt-a']").text()).toContain("3");
  });

  // @TS-032 — a stray tally (wrong questionId) is ignored; the prior view is preserved.
  it("ignores a tally whose questionId does not match the current snapshot", async () => {
    const wrapper = mountResults();
    await flushPromises();
    capturedHandlers!.onSnapshot(
      snapshot(
        "q-2",
        [
          { id: "opt-a", label: "Alpha", position: 0 },
          { id: "opt-b", label: "Bravo", position: 1 }
        ],
        { "opt-a": 5, "opt-b": 7 }
      )
    );
    await flushPromises();
    capturedHandlers!.onTally({
      pollId: "p-1",
      questionId: "q-1-STALE",
      optionId: "opt-a",
      count: 9999,
      emittedAt: new Date().toISOString()
    });
    await flushPromises();
    // opt-a's count is still 5, not 9999.
    expect(wrapper.get("[data-testid='poll-bar-opt-a']").text()).toContain("5");
    expect(wrapper.get("[data-testid='poll-bar-opt-b']").text()).toContain("7");
  });

  // @TS-033 — connection loss surfaces the paused badge; the component must not throw.
  it("renders a paused badge when the connection flips to paused", async () => {
    const wrapper = mountResults();
    await flushPromises();
    capturedHandlers!.onConnectionStateChange?.("paused");
    await flushPromises();
    expect(wrapper.get("[data-testid='poll-paused']").text()).toMatch(/paused/i);
  });

  // @TS-034 — a subsequent snapshot clears the paused indicator and restores the live view.
  it("clears the paused badge on a later snapshot", async () => {
    const wrapper = mountResults();
    await flushPromises();
    capturedHandlers!.onConnectionStateChange?.("paused");
    await flushPromises();
    expect(wrapper.find("[data-testid='poll-paused']").exists()).toBe(true);
    capturedHandlers!.onSnapshot(
      snapshot("q-9", [
        { id: "x", label: "X", position: 0 },
        { id: "y", label: "Y", position: 1 }
      ])
    );
    await flushPromises();
    expect(wrapper.find("[data-testid='poll-paused']").exists()).toBe(false);
  });

  // question-closed leaves the slide navigable and shows a soft waiting message until the next
  // snapshot (Principle IV).
  it("handles a question-closed event without unmounting", async () => {
    const wrapper = mountResults();
    await flushPromises();
    capturedHandlers!.onSnapshot(
      snapshot("q-1", [
        { id: "opt-a", label: "A", position: 0 },
        { id: "opt-b", label: "B", position: 1 }
      ])
    );
    await flushPromises();
    capturedHandlers!.onQuestionClosed?.({
      pollId: "p-1",
      questionId: "q-1",
      emittedAt: new Date().toISOString()
    } satisfies QuestionClosedEvent);
    await flushPromises();
    expect(wrapper.get("[data-testid='poll-waiting']").text()).toMatch(/closed/i);
  });

  // @TS-050 — mount with questionId + deckToken + pollId fires POST /api/deck/.../activate.
  it("fires the deck-activation POST when all three activation props are present", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    mountResults({
      slug: "my-talk",
      pollId: "p-1",
      questionId: "q-active",
      deckToken: "dtk-1"
    });
    await flushPromises();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/deck\/polls\/p-1\/activate$/);
    expect((init as RequestInit).method).toBe("POST");
    expect((init as RequestInit).headers).toMatchObject({ "X-Deck-Token": "dtk-1" });
    expect((init as RequestInit).body).toBe(JSON.stringify({ questionId: "q-active" }));
  });

  // @TS-053 / @TS-054 / @TS-055 — when the activation POST fails (401/403/5xx or network), the
  // component MUST NOT throw. The assertion is structural: the subscription still lands.
  it("never throws when the activation POST fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network down")));
    const wrapper = mountResults({
      slug: "my-talk",
      pollId: "p-1",
      questionId: "q-active",
      deckToken: "dtk-1"
    });
    await flushPromises();
    expect(capturedHandlers).not.toBeNull();
    // The DOM still renders the waiting state without throwing — Principle IV.
    expect(wrapper.get("[data-testid='poll-waiting']").text()).toMatch(/waiting/i);
  });

  // Unmounting releases the SSE subscription.
  it("stops the stream on unmount", async () => {
    const wrapper = mountResults();
    await flushPromises();
    wrapper.unmount();
    expect(unsubscribeCalls).toBe(1);
  });
});
