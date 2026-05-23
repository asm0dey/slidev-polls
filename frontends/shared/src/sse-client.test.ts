import { describe, expect, it } from "bun:test";
import { openPollStream } from "./sse-client";

// Minimal EventSource stub that records add-event-listener calls and lets the
// test trigger events synchronously. `close()` flips a flag so the test can
// assert the client releases old connections when it reconnects.
class StubEventSource {
  readonly url: string;
  listeners: Record<string, ((e: Event | MessageEvent) => void)[]> = {};
  closed = false;
  constructor(url: string) {
    this.url = url;
  }
  addEventListener(name: string, cb: (e: Event | MessageEvent) => void) {
    (this.listeners[name] ??= []).push(cb);
  }
  close() {
    this.closed = true;
  }
  emit(name: string, data?: unknown) {
    const ev =
      data !== undefined ? ({ data: JSON.stringify(data) } as MessageEvent) : new Event(name);
    (this.listeners[name] ?? []).forEach((cb) => cb(ev));
  }
}

interface ScheduledTask {
  fn: () => void;
  ms: number;
  cancelled: boolean;
}

function collectStubs() {
  const stubs: StubEventSource[] = [];
  const scheduled: ScheduledTask[] = [];
  const stateChanges: string[] = [];
  const unsubscribe = openPollStream(
    "http://localhost:8080",
    "my-talk",
    {
      onSnapshot: () => {},
      onConnectionStateChange: (s) => stateChanges.push(s)
    },
    {
      eventSourceFactory: (url) => {
        const s = new StubEventSource(url);
        stubs.push(s);
        return s as unknown as EventSource;
      },
      scheduler: (fn, ms) => {
        const task: ScheduledTask = { fn, ms, cancelled: false };
        scheduled.push(task);
        return () => {
          task.cancelled = true;
        };
      },
      minDelayMs: 100,
      maxDelayMs: 1000
    }
  );
  return { stubs, scheduled, stateChanges, unsubscribe };
}

describe("openPollStream bounded-backoff reconnect", () => {
  it("opens the configured URL on first connect", () => {
    const { stubs, unsubscribe } = collectStubs();
    expect(stubs).toHaveLength(1);
    expect(stubs[0].url).toBe("http://localhost:8080/api/polls/my-talk/stream");
    unsubscribe();
  });

  it("transitions open -> paused -> open across a reconnect", () => {
    const { stubs, scheduled, stateChanges, unsubscribe } = collectStubs();
    stubs[0].emit("open");
    stubs[0].emit("error");
    expect(stateChanges).toEqual(["open", "paused"]);
    expect(stubs[0].closed).toBe(true);

    // Fire the scheduled reconnect callback — the client should open a fresh
    // stream, and the next "open" returns the state to connected.
    scheduled[0].fn();
    expect(stubs).toHaveLength(2);
    stubs[1].emit("open");
    expect(stateChanges).toEqual(["open", "paused", "open"]);
    unsubscribe();
  });

  it("delays reconnect attempts with exponential backoff up to maxDelayMs", () => {
    const { stubs, scheduled, unsubscribe } = collectStubs();

    stubs[0].emit("error");
    expect(scheduled[0].ms).toBe(100);

    scheduled[0].fn();
    stubs[1].emit("error");
    expect(scheduled[1].ms).toBe(200);

    scheduled[1].fn();
    stubs[2].emit("error");
    expect(scheduled[2].ms).toBe(400);

    scheduled[2].fn();
    stubs[3].emit("error");
    expect(scheduled[3].ms).toBe(800);

    scheduled[3].fn();
    stubs[4].emit("error");
    // Would be 1600 but the cap is 1000; must not exceed maxDelayMs.
    expect(scheduled[4].ms).toBe(1000);

    scheduled[4].fn();
    stubs[5].emit("error");
    expect(scheduled[5].ms).toBe(1000);
    unsubscribe();
  });

  it("resets the backoff window after a successful open", () => {
    const { stubs, scheduled, unsubscribe } = collectStubs();

    stubs[0].emit("error");
    scheduled[0].fn(); // delay 100
    stubs[1].emit("error");
    scheduled[1].fn(); // delay 200
    stubs[2].emit("error");
    expect(scheduled[2].ms).toBe(400);

    // Reconnect succeeds this time.
    scheduled[2].fn();
    stubs[3].emit("open");

    // Next failure starts over at minDelayMs.
    stubs[3].emit("error");
    expect(scheduled[3].ms).toBe(100);
    unsubscribe();
  });

  it("stops reconnecting once the caller unsubscribes", () => {
    const { stubs, scheduled, unsubscribe } = collectStubs();
    stubs[0].emit("error");
    expect(scheduled[0].cancelled).toBe(false);

    unsubscribe();
    expect(scheduled[0].cancelled).toBe(true);

    // Even if the timer still fired, no new EventSource should appear after
    // unsubscribe — the client must treat unsubscribe as authoritative.
    scheduled[0].fn();
    expect(stubs).toHaveLength(1);
  });

  it("dispatches snapshot and question-closed events to their handlers", () => {
    const stubs: StubEventSource[] = [];
    const seen: string[] = [];
    const unsubscribe = openPollStream(
      "http://localhost:8080",
      "my-talk",
      {
        onSnapshot: (ev) => seen.push(`snapshot:${ev.pollId}`),
        onQuestionClosed: (ev) => seen.push(`closed:${ev.questionId}`)
      },
      {
        eventSourceFactory: (url) => {
          const s = new StubEventSource(url);
          stubs.push(s);
          return s as unknown as EventSource;
        },
        scheduler: () => () => {}
      }
    );

    stubs[0].emit("snapshot", {
      pollId: "p1",
      slug: "my-talk",
      activeQuestion: null,
      tally: [],
      emittedAt: "2026-04-19T10:00:00Z"
    });
    stubs[0].emit("question-closed", { pollId: "p1", questionId: "q1" });

    expect(seen).toEqual(["snapshot:p1", "closed:q1"]);
    unsubscribe();
  });

  it("ignores legacy `tally` events — snapshot is the only update path", () => {
    const stubs: StubEventSource[] = [];
    const seen: string[] = [];
    const unsubscribe = openPollStream(
      "http://localhost:8080",
      "my-talk",
      {
        onSnapshot: (ev) => seen.push(`snapshot:${ev.pollId}`)
      },
      {
        eventSourceFactory: (url) => {
          const s = new StubEventSource(url);
          stubs.push(s);
          return s as unknown as EventSource;
        },
        scheduler: () => () => {}
      }
    );

    // A server still emitting `tally` deltas must not crash the client; the
    // listener simply isn't registered, so the event is dropped on the floor.
    stubs[0].emit("tally", { pollId: "p1", optionId: "o1", count: 3 });
    expect(seen).toEqual([]);

    // The canonical update path is a fresh snapshot. After it, callers see the
    // new state.
    stubs[0].emit("snapshot", {
      pollId: "p1",
      slug: "my-talk",
      activeQuestion: null,
      tally: [{ optionId: "o1", count: 3 }],
      emittedAt: "2026-04-19T10:00:01Z"
    });
    expect(seen).toEqual(["snapshot:p1"]);
    unsubscribe();
  });
});
