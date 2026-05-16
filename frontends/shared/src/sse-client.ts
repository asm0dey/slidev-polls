import type { QuestionClosedEvent, SnapshotEvent } from "./types";

export interface StreamHandlers {
  onSnapshot: (ev: SnapshotEvent) => void;
  onQuestionClosed?: (ev: QuestionClosedEvent) => void;
  onConnectionStateChange?: (state: "open" | "paused") => void;
}

/** Factory that produces an {@link EventSource} for a given URL. Overridable in tests. */
export type EventSourceFactory = (url: string) => EventSource;

/** Schedules {@code fn} to run after {@code ms} milliseconds; returns a cancel handle. */
export type DelayedScheduler = (fn: () => void, ms: number) => () => void;

export interface OpenPollStreamOptions {
  eventSourceFactory?: EventSourceFactory;
  scheduler?: DelayedScheduler;
  /** Delay for the first reconnect attempt after an error. Defaults to 500ms. */
  minDelayMs?: number;
  /** Ceiling for the exponentially-growing reconnect delay. Defaults to 10s. */
  maxDelayMs?: number;
}

const DEFAULT_MIN_DELAY_MS = 500;
const DEFAULT_MAX_DELAY_MS = 10_000;

const defaultEventSourceFactory: EventSourceFactory = (url) =>
  new EventSource(url, { withCredentials: true });

const defaultScheduler: DelayedScheduler = (fn, ms) => {
  const id = setTimeout(fn, ms);
  return () => clearTimeout(id);
};

/**
 * Subscribe to the SSE stream for a single poll. The returned callback
 * unsubscribes and also cancels any pending reconnect, so callers do not have
 * to track timers themselves.
 *
 * <p>On connection failure the client flips the state to {@code "paused"},
 * closes the broken {@link EventSource}, and schedules a reconnect with
 * exponential backoff starting at {@code minDelayMs} and capped at
 * {@code maxDelayMs}. A successful {@code open} event resets the backoff so a
 * later failure starts over at {@code minDelayMs}.
 *
 * <p>This matches Principle IV (Live-Reliability Over Feature Depth): the UI
 * shows a visible "paused" badge during reconnect rather than throwing, and
 * the backoff cap keeps a long outage from blowing up to minute-long retries.
 */
export function openPollStream(
  baseUrl: string,
  slug: string,
  handlers: StreamHandlers,
  opts: OpenPollStreamOptions = {}
): () => void {
  const url = `${baseUrl.replace(/\/$/, "")}/api/polls/${encodeURIComponent(slug)}/stream`;
  const esFactory = opts.eventSourceFactory ?? defaultEventSourceFactory;
  const schedule = opts.scheduler ?? defaultScheduler;
  const minDelay = opts.minDelayMs ?? DEFAULT_MIN_DELAY_MS;
  const maxDelay = opts.maxDelayMs ?? DEFAULT_MAX_DELAY_MS;

  let currentEs: EventSource | null = null;
  let cancelRetry: (() => void) | null = null;
  let retryDelay = minDelay;
  let stopped = false;

  function connect() {
    if (stopped) {
      return;
    }
    const es = esFactory(url);
    currentEs = es;

    es.addEventListener("open", () => {
      retryDelay = minDelay;
      handlers.onConnectionStateChange?.("open");
    });

    es.addEventListener("error", () => {
      handlers.onConnectionStateChange?.("paused");
      es.close();
      if (currentEs === es) {
        currentEs = null;
      }
      const scheduledDelay = retryDelay;
      retryDelay = Math.min(retryDelay * 2, maxDelay);
      cancelRetry = schedule(() => {
        cancelRetry = null;
        connect();
      }, scheduledDelay);
    });

    es.addEventListener("snapshot", (e) => {
      handlers.onSnapshot(JSON.parse((e as MessageEvent).data) as SnapshotEvent);
    });
    es.addEventListener("question-closed", (e) => {
      handlers.onQuestionClosed?.(JSON.parse((e as MessageEvent).data) as QuestionClosedEvent);
    });
  }

  connect();

  return () => {
    stopped = true;
    if (cancelRetry) {
      cancelRetry();
      cancelRetry = null;
    }
    if (currentEs) {
      currentEs.close();
      currentEs = null;
    }
  };
}
