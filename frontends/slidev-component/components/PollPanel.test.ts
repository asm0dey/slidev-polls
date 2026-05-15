import { describe, it, expect, vi, afterEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import PollPanel from "./PollPanel.vue";

// jsdom does not implement IntersectionObserver. PollPanel relies on IO as the
// single source of truth for "this panel is on-screen, activate now" (since
// commit-9864688 removed the mount-time activate race), so tests that exercise
// activate/close edges need a stub they can drive manually.
class FakeIntersectionObserver {
  static instances: FakeIntersectionObserver[] = [];
  callback: IntersectionObserverCallback;
  targets: Element[] = [];
  root = null;
  rootMargin = "";
  thresholds: number[] = [];
  constructor(cb: IntersectionObserverCallback) {
    this.callback = cb;
    FakeIntersectionObserver.instances.push(this);
  }
  observe(t: Element) {
    this.targets.push(t);
  }
  unobserve() {}
  disconnect() {
    this.targets = [];
  }
  takeRecords() {
    return [];
  }
  trigger(ratio: number) {
    const entries = this.targets.map(
      (t) =>
        ({
          target: t,
          isIntersecting: ratio >= 0.1,
          intersectionRatio: ratio,
          boundingClientRect: t.getBoundingClientRect(),
          intersectionRect: t.getBoundingClientRect(),
          rootBounds: null,
          time: 0
        }) as IntersectionObserverEntry
    );
    this.callback(entries, this as unknown as IntersectionObserver);
  }
}

function installFakeIO(): typeof FakeIntersectionObserver {
  FakeIntersectionObserver.instances = [];
  (globalThis as { IntersectionObserver: unknown }).IntersectionObserver = FakeIntersectionObserver;
  return FakeIntersectionObserver;
}
function uninstallFakeIO() {
  delete (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver;
  FakeIntersectionObserver.instances = [];
}

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

  it("registers the snapshot in the shared store", async () => {
    authState.value = "anonymous";
    const w = mount(PollPanel, { props: { slug: "shared-slug" } });
    await flushPromises();
    const { usePollResults } = await import("../composables/usePollResults");
    const r = usePollResults("shared-slug");
    expect(r.value?.activeQuestion?.id).toBe("q1");
    expect(r.value?.tally).toEqual([
      { optionId: "a", count: 1 },
      { optionId: "b", count: 3 }
    ]);
    w.unmount();
  });

  it("retains last-known snapshot in the store on question-closed", async () => {
    authState.value = "anonymous";
    const sharedMod = await import("@slidev-polls/shared");
    const original = sharedMod.openPollStream;
    (sharedMod as { openPollStream: unknown }).openPollStream = (
      _b: string,
      _s: string,
      handlers: {
        onSnapshot: (e: unknown) => void;
        onQuestionClosed: (e: unknown) => void;
        onConnectionStateChange: (s: string) => void;
      }
    ) => {
      handlers.onSnapshot({
        pollId: "p1",
        slug: "closing-slug",
        emittedAt: "now",
        activeQuestion: {
          id: "q9",
          prompt: "Pick",
          ordinal: 1,
          options: [{ id: "a", label: "A", position: 0 }]
        },
        tally: [{ optionId: "a", count: 2 }]
      });
      handlers.onQuestionClosed({ pollId: "p1", questionId: "q9", emittedAt: "now" });
      return () => {};
    };

    const w = mount(PollPanel, { props: { slug: "closing-slug" } });
    await flushPromises();
    // Local panel shows the "closed" notice, but the shared store keeps the
    // last-known activeQuestion + tally so aggregator slides can compose
    // combined results after individual slides have left.
    expect(w.find("[data-testid='poll-waiting']").text()).toContain("Question closed");
    const { usePollResults } = await import("../composables/usePollResults");
    expect(usePollResults("closing-slug").value?.activeQuestion?.id).toBe("q9");
    expect(usePollResults("closing-slug").value?.tally).toEqual([{ optionId: "a", count: 2 }]);

    (sharedMod as { openPollStream: unknown }).openPollStream = original;
    w.unmount();
  });

  it("registers under the `name` prop when provided", async () => {
    authState.value = "anonymous";
    const w = mount(PollPanel, { props: { slug: "named-slug", name: "q-warmup" } });
    await flushPromises();
    const { usePollResults } = await import("../composables/usePollResults");
    expect(usePollResults("q-warmup").value?.activeQuestion?.id).toBe("q1");
    expect(usePollResults("named-slug").value).toBeNull();
    w.unmount();
  });

  it("POSTs /close when the panel unmounts after IntersectionObserver activated it", async () => {
    authState.value = "signed-in";
    const FakeIO = installFakeIO();
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 200 }));

    const w = mount(PollPanel, {
      props: {
        slug: "s",
        pollId: "poll-close-test",
        questionId: "q-close-test",
        server: "https://api.test"
      },
      attachTo: document.body
    });
    await flushPromises();

    // Simulate the slide becoming visible — IO is the only path that drives
    // activate, so without this the panel never marks itself active and the
    // unmount has nothing to close.
    FakeIO.instances[0]!.trigger(1);
    await flushPromises();

    fetchSpy.mockClear();
    fetchSpy.mockResolvedValue(new Response(null, { status: 200 }));

    w.unmount();
    await flushPromises();

    const closeCalls = fetchSpy.mock.calls.filter(([url]) =>
      String(url).includes("/api/deck/polls/poll-close-test/close")
    );
    expect(closeCalls.length).toBeGreaterThan(0);
    const [, init] = closeCalls[0];
    expect((init as RequestInit).method).toBe("POST");
    expect(((init as RequestInit).headers as Record<string, string>)["X-Deck-Token"]).toBe("tok");
    // Body scopes the close to this panel's questionId so the backend can ignore a
    // stale close that arrives after a different question became active.
    expect(JSON.parse(String((init as RequestInit).body))).toEqual({
      questionId: "q-close-test"
    });

    fetchSpy.mockRestore();
    uninstallFakeIO();
  });

  it("does NOT POST /activate at mount time (avoids the slidev all-slides-mounted race)", async () => {
    // Slidev keeps every slide mounted simultaneously. An eager mount-time
    // activate would race across N PollPanels and the server would honour the
    // last write — voters watch the active question cycle through every poll
    // in the deck before settling on whichever panel finished its POST last.
    // Only the panel the IntersectionObserver confirms is on-screen should
    // activate.
    authState.value = "signed-in";
    installFakeIO();
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 200 }));

    const w = mount(PollPanel, {
      props: {
        slug: "s",
        pollId: "poll-no-mount-race",
        questionId: "q-no-mount-race",
        server: "https://api.test"
      },
      attachTo: document.body
    });
    await flushPromises();

    const activates = fetchSpy.mock.calls.filter(([url]) =>
      String(url).endsWith("/api/deck/polls/poll-no-mount-race/activate")
    );
    expect(activates.length).toBe(0);

    w.unmount();
    fetchSpy.mockRestore();
    uninstallFakeIO();
  });

  it("POSTs /activate when IntersectionObserver reports the panel is visible", async () => {
    authState.value = "signed-in";
    const FakeIO = installFakeIO();
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 200 }));

    const w = mount(PollPanel, {
      props: {
        slug: "s",
        pollId: "poll-io-activate",
        questionId: "q-io-activate",
        server: "https://api.test"
      },
      attachTo: document.body
    });
    await flushPromises();

    FakeIO.instances[0]!.trigger(1);
    await flushPromises();

    const activates = fetchSpy.mock.calls.filter(([url]) =>
      String(url).endsWith("/api/deck/polls/poll-io-activate/activate")
    );
    expect(activates.length).toBe(1);

    w.unmount();
    fetchSpy.mockRestore();
    uninstallFakeIO();
  });

  it.todo("POSTs /close when the slide scrolls below the hysteresis threshold (intersect-leave)");
});
