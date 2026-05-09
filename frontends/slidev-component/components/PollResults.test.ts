import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { nextTick, ref } from "vue";
import type {
  QuestionClosedEvent,
  SnapshotEvent,
  StreamHandlers,
  TallyDeltaEvent
} from "@slidev-polls/shared";
import type { DeckAuthState, DeckAuthStatus, UseDeckAuthReturn } from "../composables/useDeckAuth";
import PollResults from "./PollResults.vue";

// Tests mirror the live-results + activation-gating scenarios:
//   @TS-030 — snapshot renders the active question with options and initial tallies
//   @TS-031 — an active-question-change snapshot rotates the UI
//   @TS-032 — a stray tally whose questionId does NOT match the current snapshot is ignored
//   @TS-033 — connection loss renders the "live updates paused" badge; component does not throw
//   @TS-034 — a later snapshot clears the paused indicator
//   @TS-050 / @TS-053 / @TS-054 / @TS-055 — activation POST drives off the composable stub now
//   @TS-120 / @TS-121 — anonymous mount issues zero activation fetches (FR-007)
//   @TS-122 — signed-in mount issues exactly one activation POST with X-Deck-Token
//   @TS-123 — 401 DECK_TOKEN_INVALID flips composable to revoked

let capturedHandlers: StreamHandlers | null = null;
let unsubscribeCalls = 0;

vi.mock("@slidev-polls/shared", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@slidev-polls/shared")>();
  return {
    ...actual,
    openPollStream: (_baseUrl: string, _slug: string, handlers: StreamHandlers): (() => void) => {
      capturedHandlers = handlers;
      return () => {
        unsubscribeCalls++;
      };
    }
  };
});

type AuthOverrides = Partial<{
  status: DeckAuthStatus;
  state: Partial<DeckAuthState>;
}>;

let authStub: UseDeckAuthReturn & {
  _calls: { markRevoked: number; signOut: number };
} = makeAuthStub();

function makeAuthStub(overrides: AuthOverrides = {}): UseDeckAuthReturn & {
  _calls: { markRevoked: number; signOut: number };
} {
  const status = ref<DeckAuthStatus>(overrides.status ?? "anonymous");
  const state = ref<DeckAuthState>({
    token: null,
    tokenId: null,
    pollId: null,
    label: null,
    verifiedAt: null,
    ...(overrides.state ?? {})
  });
  const message = ref<string | null>(null);
  const _calls = { markRevoked: 0, signOut: 0 };
  return {
    status,
    state,
    message,
    signIn: async () => {},
    signInWithCredentials: async () => {},
    signOut: () => {
      _calls.signOut++;
    },
    markRevoked: () => {
      _calls.markRevoked++;
      status.value = "revoked";
    },
    _calls
  };
}

vi.mock("../composables/useDeckAuth", () => ({
  useDeckAuth: () => authStub
}));

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
    authStub = makeAuthStub();
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
    expect(wrapper.get("[data-option-id='opt-a']").text()).toContain("OpenJDK");
    expect(wrapper.get("[data-option-id='opt-b']").text()).toContain("GraalVM");
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
    expect(wrapper.get("[data-option-id='opt-a']").text()).toContain("3");
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
    expect(wrapper.get("[data-option-id='opt-a']").text()).toContain("5");
    expect(wrapper.get("[data-option-id='opt-b']").text()).toContain("7");
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

  // question-closed keeps the slide navigable and shows a soft waiting message (Principle IV).
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

  // @TS-120 / @TS-121 — anonymous mount MUST NOT issue any activation fetch (FR-007).
  it("TS-120 / TS-121: anonymous mount issues zero activation fetches", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    authStub = makeAuthStub({ status: "anonymous" });
    mountResults({
      slug: "my-talk",
      pollId: "p-1",
      questionId: "q-active"
    });
    await flushPromises();
    for (const [url] of fetchMock.mock.calls) {
      expect(url).not.toMatch(/\/api\/deck\/polls\//);
    }
  });

  // @TS-122 / @TS-050 — signed-in mount fires exactly one POST with the X-Deck-Token header.
  it("TS-122: signed-in mount issues exactly one activation POST with X-Deck-Token", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    authStub = makeAuthStub({
      status: "signed-in",
      state: {
        token: "dtk-1",
        tokenId: "11111111-1111-1111-1111-111111111111",
        pollId: "p-1",
        label: "Laptop"
      }
    });
    mountResults({
      slug: "my-talk",
      pollId: "p-1",
      questionId: "q-active"
    });
    await flushPromises();
    const activationCalls = fetchMock.mock.calls.filter(([url]) =>
      String(url).includes("/api/deck/polls/")
    );
    expect(activationCalls).toHaveLength(1);
    const [url, init] = activationCalls[0];
    expect(url).toMatch(/\/api\/deck\/polls\/p-1\/activate$/);
    expect((init as RequestInit).method).toBe("POST");
    expect((init as RequestInit).headers).toMatchObject({ "X-Deck-Token": "dtk-1" });
    expect((init as RequestInit).body).toBe(JSON.stringify({ questionId: "q-active" }));
  });

  // @TS-123 — 401 DECK_TOKEN_INVALID on the activation POST flips composable to revoked
  // without mutating any local state beyond the composable (FR-008, Principle IV).
  it("TS-123: activation 401 DECK_TOKEN_INVALID flips composable to revoked", async () => {
    const revokedBody = JSON.stringify({ code: "DECK_TOKEN_INVALID", message: "revoked" });
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(revokedBody, {
        status: 401,
        headers: { "content-type": "application/json" }
      })
    );
    vi.stubGlobal("fetch", fetchMock);
    authStub = makeAuthStub({
      status: "signed-in",
      state: {
        token: "dtk-1",
        tokenId: "11111111-1111-1111-1111-111111111111",
        pollId: "p-1",
        label: "Laptop"
      }
    });
    mountResults({
      slug: "my-talk",
      pollId: "p-1",
      questionId: "q-active"
    });
    await flushPromises();
    await nextTick();
    expect(authStub._calls.markRevoked).toBe(1);
    expect(authStub.status.value).toBe("revoked");
  });

  // @TS-053 / @TS-054 / @TS-055 — when the activation POST fails (401/403/5xx or network),
  // the component MUST NOT throw. Structural assertion: subscription still lands.
  it("never throws when the activation POST fails (non-revoked network error)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network down")));
    authStub = makeAuthStub({
      status: "signed-in",
      state: {
        token: "dtk-1",
        tokenId: "11111111-1111-1111-1111-111111111111",
        pollId: "p-1",
        label: "Laptop"
      }
    });
    const wrapper = mountResults({
      slug: "my-talk",
      pollId: "p-1",
      questionId: "q-active"
    });
    await flushPromises();
    expect(capturedHandlers).not.toBeNull();
    expect(wrapper.get("[data-testid='poll-waiting']").text()).toMatch(/waiting/i);
  });

  // @TS-140 — anonymous mount renders live results visualisation and NO "set active"
  // affordance (FR-012). The slide is viewer-grade; presenter controls live outside it.
  it("TS-140: anonymous mount renders live results and no activation affordance", async () => {
    authStub = makeAuthStub({ status: "anonymous" });
    const wrapper = mountResults({ slug: "my-talk", questionId: "q-1", pollId: "p-1" });
    await flushPromises();
    capturedHandlers!.onSnapshot(
      snapshot("q-1", [
        { id: "opt-a", label: "A", position: 0 },
        { id: "opt-b", label: "B", position: 1 }
      ])
    );
    await flushPromises();
    expect(wrapper.find("[data-option-id='opt-a']").exists()).toBe(true);
    // No element that would let a viewer flip the active question.
    expect(wrapper.find("[data-testid='set-active']").exists()).toBe(false);
    expect(wrapper.find("button.activate").exists()).toBe(false);
    expect(wrapper.find("[data-testid='deck-activate']").exists()).toBe(false);
  });

  // @TS-141 — a TallyDeltaEvent arriving at t=0 is reflected in the DOM at t ≤ 2000 ms.
  it("TS-141: tally update reflected within the 2s live-update budget", async () => {
    vi.useFakeTimers();
    try {
      const wrapper = mountResults({ slug: "my-talk" });
      await flushPromises();
      capturedHandlers!.onSnapshot(
        snapshot("q-1", [
          { id: "opt-a", label: "A", position: 0 },
          { id: "opt-b", label: "B", position: 1 }
        ])
      );
      await flushPromises();
      capturedHandlers!.onTally({
        pollId: "p-1",
        questionId: "q-1",
        optionId: "opt-a",
        count: 7,
        emittedAt: new Date().toISOString()
      });
      vi.advanceTimersByTime(2000);
      await flushPromises();
      expect(wrapper.get("[data-option-id='opt-a']").text()).toContain("7");
    } finally {
      vi.useRealTimers();
    }
  });

  // @TS-142 — SSE drop shows "live updates paused"; unmount stays clean.
  it("TS-142: SSE drop shows paused indicator, unmount stays responsive", async () => {
    const wrapper = mountResults({ slug: "my-talk" });
    await flushPromises();
    capturedHandlers!.onConnectionStateChange?.("paused");
    await flushPromises();
    expect(wrapper.get("[data-testid='poll-paused']").text()).toMatch(/live updates paused/i);
    wrapper.unmount();
    expect(unsubscribeCalls).toBe(1);
  });

  // @TS-143 — reconnect clears the paused indicator and resumes rendering live snapshots.
  it("TS-143: SSE reconnect clears paused and resumes updates", async () => {
    const wrapper = mountResults({ slug: "my-talk" });
    await flushPromises();
    capturedHandlers!.onConnectionStateChange?.("paused");
    await flushPromises();
    expect(wrapper.find("[data-testid='poll-paused']").exists()).toBe(true);
    capturedHandlers!.onConnectionStateChange?.("open");
    capturedHandlers!.onSnapshot(
      snapshot("q-2", [
        { id: "opt-x", label: "X", position: 0 },
        { id: "opt-y", label: "Y", position: 1 }
      ])
    );
    await flushPromises();
    expect(wrapper.find("[data-testid='poll-paused']").exists()).toBe(false);
    expect(wrapper.get("[data-option-id='opt-x']").text()).toContain("X");
  });

  // @TS-144 — a slide mounted with questionId=Q1 keeps showing Q1's results even when a
  // Q2 activation snapshot arrives for the same poll. The local props.questionId wins.
  it("TS-144: local questionId is independent of a remote Q2 activation", async () => {
    const wrapper = mountResults({ slug: "my-talk", questionId: "q-1", pollId: "p-1" });
    await flushPromises();
    capturedHandlers!.onSnapshot(
      snapshot(
        "q-1",
        [
          { id: "opt-a", label: "Alpha", position: 0 },
          { id: "opt-b", label: "Bravo", position: 1 }
        ],
        { "opt-a": 2, "opt-b": 3 }
      )
    );
    await flushPromises();
    // Presenter activates Q2 elsewhere — backend emits a Q2-scoped snapshot.
    capturedHandlers!.onSnapshot(
      snapshot("q-2", [
        { id: "opt-c", label: "Charlie", position: 0 },
        { id: "opt-d", label: "Delta", position: 1 }
      ])
    );
    await flushPromises();
    // The Q1 slide continues to render Q1's options — a Q2 option MUST NOT appear.
    expect(wrapper.get("[data-option-id='opt-a']").text()).toContain("Alpha");
    expect(wrapper.find("[data-option-id='opt-c']").exists()).toBe(false);
    // Tallies for Q1 still land on the Q1 view.
    capturedHandlers!.onTally({
      pollId: "p-1",
      questionId: "q-1",
      optionId: "opt-a",
      count: 9,
      emittedAt: new Date().toISOString()
    });
    await flushPromises();
    expect(wrapper.get("[data-option-id='opt-a']").text()).toContain("9");
  });

  // Unmounting releases the SSE subscription.
  it("stops the stream on unmount", async () => {
    const wrapper = mountResults();
    await flushPromises();
    wrapper.unmount();
    expect(unsubscribeCalls).toBe(1);
  });
});
