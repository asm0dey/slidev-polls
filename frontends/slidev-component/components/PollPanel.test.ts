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
          minSelections: 1,
          maxSelections: 1,
          options: [
            { id: "a", label: "React", position: 0 },
            { id: "b", label: "Vue", position: 1 }
          ]
        },
        tally: [
          { optionId: "a", count: 1 },
          { optionId: "b", count: 3 }
        ],
        voterCount: 4
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
          minSelections: 1,
          maxSelections: 1,
          options: [{ id: "a", label: "A", position: 0 }]
        },
        tally: [{ optionId: "a", count: 2 }],
        voterCount: 2
      });
      handlers.onQuestionClosed({ pollId: "p1", questionId: "q9", emittedAt: "now" });
      return () => {};
    };

    const w = mount(PollPanel, { props: { slug: "closing-slug" } });
    await flushPromises();
    // Panel keeps rendering ResultsPanel with the final tally so the deck
    // (and any PDF export) preserves the visible results after the presenter
    // closes the question. The shared store also retains the snapshot for
    // aggregator slides composing combined results across the deck.
    expect(w.find("[data-testid='results-panel']").exists()).toBe(true);
    expect(w.find("[data-testid='poll-waiting']").exists()).toBe(false);
    expect(w.text()).toContain("Pick");
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

  it("activates on mount when wrapped in a visible .slidev-page (Slidev path)", async () => {
    // Slidev path: when the panel finds a .slidev-page ancestor it uses a
    // MutationObserver on the page's `style` attribute rather than IO. A panel
    // mounted inside an already-visible slide must activate immediately.
    authState.value = "signed-in";
    const slidePage = document.createElement("div");
    slidePage.className = "slidev-page";
    document.body.appendChild(slidePage);
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 200 }));

    const w = mount(PollPanel, {
      props: {
        slug: "s",
        pollId: "poll-slidev-visible",
        questionId: "q-slidev-visible",
        server: "https://api.test"
      },
      attachTo: slidePage
    });
    await flushPromises();

    const activates = fetchSpy.mock.calls.filter(([url]) =>
      String(url).endsWith("/api/deck/polls/poll-slidev-visible/activate")
    );
    expect(activates.length).toBe(1);

    w.unmount();
    slidePage.remove();
    fetchSpy.mockRestore();
  });

  it("POSTs /close when the .slidev-page parent toggles to display:none (slide navigated away)", async () => {
    // The bug: Slidev sets `display: none` on the leaving slide. IO does not
    // reliably deliver an entry for that state change, so the panel never
    // posted /close and voters kept seeing the now-stale poll. The fix watches
    // the slide-page's style attribute and drives close off the transition.
    authState.value = "signed-in";
    const slidePage = document.createElement("div");
    slidePage.className = "slidev-page";
    document.body.appendChild(slidePage);
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 200 }));

    const w = mount(PollPanel, {
      props: {
        slug: "s",
        pollId: "poll-slide-leave",
        questionId: "q-slide-leave",
        server: "https://api.test"
      },
      attachTo: slidePage
    });
    await flushPromises();
    fetchSpy.mockClear();

    // Presenter navigates to a different slide — Slidev sets display:none
    // on this slide's .slidev-page.
    slidePage.style.display = "none";
    await flushPromises();

    const closeCalls = fetchSpy.mock.calls.filter(([url]) =>
      String(url).endsWith("/api/deck/polls/poll-slide-leave/close")
    );
    expect(closeCalls.length).toBe(1);
    const [, init] = closeCalls[0];
    expect((init as RequestInit).method).toBe("POST");
    expect(JSON.parse(String((init as RequestInit).body))).toEqual({
      questionId: "q-slide-leave"
    });

    w.unmount();
    slidePage.remove();
    fetchSpy.mockRestore();
  });

  it("re-activates when the .slidev-page parent un-hides (slide navigated back)", async () => {
    authState.value = "signed-in";
    const slidePage = document.createElement("div");
    slidePage.className = "slidev-page";
    slidePage.style.display = "none";
    document.body.appendChild(slidePage);
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 200 }));

    const w = mount(PollPanel, {
      props: {
        slug: "s",
        pollId: "poll-slide-return",
        questionId: "q-slide-return",
        server: "https://api.test"
      },
      attachTo: slidePage
    });
    await flushPromises();

    // Panel mounted while slide was hidden — must not activate yet.
    expect(
      fetchSpy.mock.calls.filter(([url]) =>
        String(url).endsWith("/api/deck/polls/poll-slide-return/activate")
      ).length
    ).toBe(0);

    // Presenter navigates back to this slide.
    slidePage.style.display = "";
    await flushPromises();

    expect(
      fetchSpy.mock.calls.filter(([url]) =>
        String(url).endsWith("/api/deck/polls/poll-slide-return/activate")
      ).length
    ).toBe(1);

    w.unmount();
    slidePage.remove();
    fetchSpy.mockRestore();
  });

  it.todo("POSTs /close when the slide scrolls below the hysteresis threshold (intersect-leave)");

  describe("render context gate", () => {
    // In presenter mode Slidev mounts the next-slide preview (the
    // `previewNext` render context) and the overview as fully-live component
    // copies. Those copies must render results read-only and never POST
    // activate/close — otherwise the preview panel for the *next* question
    // fights the on-screen panel for the active question and the deck flaps.
    afterEach(async () => {
      const { __setRenderContext } = (await import("@slidev/client")) as unknown as {
        __setRenderContext: (c: string) => void;
      };
      __setRenderContext("slide");
    });

    it("does NOT POST /activate when rendered in the previewNext context", async () => {
      authState.value = "signed-in";
      const { __setRenderContext } = (await import("@slidev/client")) as unknown as {
        __setRenderContext: (c: string) => void;
      };
      __setRenderContext("previewNext");

      const slidePage = document.createElement("div");
      slidePage.className = "slidev-page";
      document.body.appendChild(slidePage);
      const fetchSpy = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValue(new Response(null, { status: 200 }));

      const w = mount(PollPanel, {
        props: {
          slug: "s",
          pollId: "poll-preview-ctx",
          questionId: "q-preview-ctx",
          server: "https://api.test"
        },
        attachTo: slidePage
      });
      await flushPromises();

      const activates = fetchSpy.mock.calls.filter(([url]) =>
        String(url).endsWith("/api/deck/polls/poll-preview-ctx/activate")
      );
      expect(activates.length).toBe(0);

      w.unmount();
      slidePage.remove();
      fetchSpy.mockRestore();
    });
  });

  describe("CORS hint", () => {
    // Replace the default openPollStream mock with one that captures handlers
    // so each test can drive paused / snapshot edges manually.
    type Handlers = {
      onSnapshot: (e: unknown) => void;
      onQuestionClosed: (e: unknown) => void;
      onConnectionStateChange: (s: string) => void;
    };

    async function installStreamCapture() {
      const sharedMod = await import("@slidev-polls/shared");
      const original = sharedMod.openPollStream;
      const captured: { handlers: Handlers | null } = { handlers: null };
      (sharedMod as { openPollStream: unknown }).openPollStream = (
        _b: string,
        _s: string,
        handlers: Handlers
      ) => {
        captured.handlers = handlers;
        return () => {};
      };
      return {
        captured,
        restore: () => {
          (sharedMod as { openPollStream: unknown }).openPollStream = original;
        }
      };
    }

    function makeSnapshot(slug: string, qid: string) {
      return {
        pollId: "p1",
        slug,
        emittedAt: "now",
        activeQuestion: {
          id: qid,
          prompt: "Pick",
          ordinal: 1,
          minSelections: 1,
          maxSelections: 1,
          options: [{ id: "a", label: "A", position: 0 }]
        },
        tally: [{ optionId: "a", count: 1 }],
        voterCount: 1
      };
    }

    afterEach(() => {
      vi.restoreAllMocks();
    });

    it("shows CORS hint when paused twice and cross-origin fetch rejects with TypeError", async () => {
      authState.value = "anonymous";
      const { captured, restore } = await installStreamCapture();

      // mount-time historical snapshot resolves; subsequent probe rejects
      // with TypeError; same-origin HEAD resolves.
      const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
        const url = String(input);
        const method = (init as RequestInit | undefined)?.method ?? "GET";
        if (method === "HEAD") return Promise.resolve(new Response(null, { status: 200 }));
        // Same-origin (jsdom) requests resolve; cross-origin probe rejects.
        if (url.startsWith("https://api.test/")) {
          return Promise.reject(new TypeError("Failed to fetch"));
        }
        return Promise.resolve(new Response(null, { status: 200 }));
      });

      const w = mount(PollPanel, {
        props: {
          slug: "cors-slug",
          pollId: "poll-cors",
          questionId: "q-cors",
          server: "https://api.test"
        }
      });
      await flushPromises();

      captured.handlers!.onConnectionStateChange("paused");
      await flushPromises();
      captured.handlers!.onConnectionStateChange("paused");
      await flushPromises();
      // Probe + same-origin HEAD are both promises; flush a second microtask
      // tick to let the .then chains settle before asserting on the DOM.
      await flushPromises();

      const hint = w.find('[data-testid="poll-cors-hint"]');
      expect(hint.exists()).toBe(true);
      expect(hint.text()).toContain(window.location.origin);
      expect(hint.text()).toContain("Allowed origins");
      expect(hint.attributes("role")).toBe("alert");

      fetchSpy.mockRestore();
      restore();
      w.unmount();
    });

    it("does NOT show CORS hint when backend reachable but returns 5xx", async () => {
      authState.value = "anonymous";
      const { captured, restore } = await installStreamCapture();

      const fetchSpy = vi
        .spyOn(globalThis, "fetch")
        .mockImplementation(() => Promise.resolve(new Response(null, { status: 503 })));

      const w = mount(PollPanel, {
        props: {
          slug: "cors-503",
          pollId: "poll-503",
          questionId: "q-503",
          server: "https://api.test"
        }
      });
      await flushPromises();

      captured.handlers!.onConnectionStateChange("paused");
      await flushPromises();
      captured.handlers!.onConnectionStateChange("paused");
      await flushPromises();
      await flushPromises();

      expect(w.find('[data-testid="poll-cors-hint"]').exists()).toBe(false);

      fetchSpy.mockRestore();
      restore();
      w.unmount();
    });

    it("does NOT show CORS hint when both probes fail (offline)", async () => {
      authState.value = "anonymous";
      const { captured, restore } = await installStreamCapture();

      const fetchSpy = vi
        .spyOn(globalThis, "fetch")
        .mockImplementation(() => Promise.reject(new TypeError("offline")));

      const w = mount(PollPanel, {
        props: {
          slug: "cors-offline",
          pollId: "poll-offline",
          questionId: "q-offline",
          server: "https://api.test"
        }
      });
      await flushPromises();

      captured.handlers!.onConnectionStateChange("paused");
      await flushPromises();
      captured.handlers!.onConnectionStateChange("paused");
      await flushPromises();
      await flushPromises();

      expect(w.find('[data-testid="poll-cors-hint"]').exists()).toBe(false);

      fetchSpy.mockRestore();
      restore();
      w.unmount();
    });

    it("clears CORS hint when a snapshot arrives after recovery", async () => {
      authState.value = "anonymous";
      const { captured, restore } = await installStreamCapture();

      const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
        const url = String(input);
        const method = (init as RequestInit | undefined)?.method ?? "GET";
        if (method === "HEAD") return Promise.resolve(new Response(null, { status: 200 }));
        if (url.startsWith("https://api.test/")) {
          return Promise.reject(new TypeError("Failed to fetch"));
        }
        return Promise.resolve(new Response(null, { status: 200 }));
      });

      const w = mount(PollPanel, {
        props: {
          slug: "cors-recover",
          pollId: "poll-recover",
          questionId: "q-recover",
          server: "https://api.test"
        }
      });
      await flushPromises();

      captured.handlers!.onConnectionStateChange("paused");
      await flushPromises();
      captured.handlers!.onConnectionStateChange("paused");
      await flushPromises();
      await flushPromises();

      expect(w.find('[data-testid="poll-cors-hint"]').exists()).toBe(true);

      captured.handlers!.onSnapshot(makeSnapshot("cors-recover", "q-recover"));
      await flushPromises();

      expect(w.find('[data-testid="poll-cors-hint"]').exists()).toBe(false);

      fetchSpy.mockRestore();
      restore();
      w.unmount();
    });

    it("only shows CORS hint after at least one reconnect attempt", async () => {
      authState.value = "anonymous";
      const { captured, restore } = await installStreamCapture();

      const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
        const url = String(input);
        const method = (init as RequestInit | undefined)?.method ?? "GET";
        if (method === "HEAD") return Promise.resolve(new Response(null, { status: 200 }));
        if (url.startsWith("https://api.test/")) {
          return Promise.reject(new TypeError("Failed to fetch"));
        }
        return Promise.resolve(new Response(null, { status: 200 }));
      });

      const w = mount(PollPanel, {
        props: {
          slug: "cors-once",
          pollId: "poll-once",
          questionId: "q-once",
          server: "https://api.test"
        }
      });
      await flushPromises();

      // First pause — single transient blip, no probe yet.
      captured.handlers!.onConnectionStateChange("paused");
      await flushPromises();
      await flushPromises();
      expect(w.find('[data-testid="poll-cors-hint"]').exists()).toBe(false);

      // Second pause — now we probe and surface the hint.
      captured.handlers!.onConnectionStateChange("paused");
      await flushPromises();
      await flushPromises();
      expect(w.find('[data-testid="poll-cors-hint"]').exists()).toBe(true);

      fetchSpy.mockRestore();
      restore();
      w.unmount();
    });
  });
});
