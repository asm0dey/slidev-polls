<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, useAttrs, watch } from "vue";
// Slidev's addon loader does not execute the package's `index.ts`, so the
// side-effect tokens.css import from there never runs in a deck. Re-import
// it from this top-level component so `--sp-*` custom properties resolve
// (without it, every `background: var(--sp-*)` collapses to transparent).
import "@slidev-polls/shared/tokens.css";
import {
  openPollStream,
  ResultsPanel,
  type QuestionClosedEvent,
  type SnapshotEvent,
  type TallyDeltaEvent
} from "@slidev-polls/shared";
import { useDeckAuth } from "../composables/useDeckAuth";
import { useSlidevTheme } from "../composables/useSlidevTheme";
import { getConfiguredBackend } from "../composables/configureDeckAuthBackend";
import { setPollResults } from "../composables/usePollResults";
import { slideWidth, useSlideContext } from "@slidev/client";
import PollQrButton from "./PollQrButton.vue";

const props = defineProps<{
  slug: string;
  server?: string;
  questionId?: string;
  pollId?: string;
  name?: string;
}>();

// Key under which this panel publishes into the shared poll-results store.
// Authors set `name` for ergonomic lookups (`usePollResults("q1")`); when
// omitted, fall back to a deterministic key so multiple panels on the same
// slug — one per question — don't overwrite each other.
const resultsKey = computed(
  () => props.name ?? (props.questionId ? `${props.slug}::${props.questionId}` : props.slug)
);

const auth = useDeckAuth();

// Slidev exposes parsed headmatter via $slidev.configs and per-slide
// frontmatter via $frontmatter. We accept pollServer in either, and fall
// through to the legacy configureDeckAuthBackend() call. This composable
// uses Vue inject under the hood — safe to call here because PollPanel
// always renders inside a slide's component tree.
const slideCtx = (() => {
  try {
    return useSlideContext();
  } catch {
    return null; // outside slidev (unit tests) — fall back to other sources.
  }
})();
const headmatterServer = (() => {
  const fm = (slideCtx?.$frontmatter ?? {}) as Record<string, unknown>;
  if (typeof fm.pollServer === "string" && fm.pollServer.length > 0) return fm.pollServer;
  const cfgs = (slideCtx?.$slidev?.configs ?? {}) as Record<string, unknown>;
  if (typeof cfgs.pollServer === "string" && cfgs.pollServer.length > 0) return cfgs.pollServer;
  return "";
})();

const attrs = useAttrs();
const isDev = (import.meta as unknown as { env?: { DEV?: boolean } }).env?.DEV === true;
if (isDev && "deckToken" in attrs) {
  // eslint-disable-next-line no-console
  console.warn(
    "[slidev-polls] `deckToken` prop removed in 002; sign in via the in-deck auth control."
  );
}

const root = ref<HTMLElement | null>(null);
const theme = useSlidevTheme(root);

const snapshot = ref<SnapshotEvent | null>(null);
const paused = ref(false);
const closedNotice = ref<string | null>(null);
let stop: (() => void) | null = null;

// Tracks the most recent intent we successfully posted. The server is idempotent already,
// but this skips the wasted round-trip when a slide jitter fires the same edge twice.
let lastSentIntent: "open" | "closed" | null = null;
// Slidev keeps every slide mounted simultaneously. The observer-leave branch fires for
// every off-screen panel — including those that never visibly entered. Without this
// gate, an off-screen panel that auto-activated via the auth-watcher signed-in transition
// would later fire close from observer-leave, racing the on-screen panel's activation.
// We only fire observer-driven close when an observer-enter actually happened.
let observerOpened = false;

// `layout: center` wraps the slide body in an inline-block whose intrinsic
// width tracks its content, so a percentage on .sp-pollpanel resolves to
// content width and shrinks unboundedly. Pin to 85% of slidev's slide
// canvas (slideWidth from @slidev/client/env, derived from canvasWidth in
// the headmatter — default 980px).
const panelWidth = computed(() => `${Math.round(slideWidth.value * 0.85)}px`);

const voterUrl = computed(() => {
  // QR encodes the voter SPA URL. The backend SPA-forwards /${slug} to the
  // voter app, so we hit the same host as the poll server when one is
  // configured, falling back to the deck's own origin for same-origin setups.
  const base = (props.server ?? "") || headmatterServer || getConfiguredBackend();
  const origin = base || (typeof window !== "undefined" ? window.location.origin : "");
  return `${origin.replace(/\/$/, "")}/${props.slug}`;
});

const panelQuestion = computed(() => {
  const s = snapshot.value;
  if (!s?.activeQuestion) return null;
  return {
    prompt: s.activeQuestion.prompt,
    options: s.activeQuestion.options.map((o) => ({ id: o.id, label: o.label })),
    tally: s.tally
  };
});

function isElementVisible(el: HTMLElement): boolean {
  const r = el.getBoundingClientRect();
  if (r.width === 0 || r.height === 0) return false;
  const cs = getComputedStyle(el);
  if (cs.visibility === "hidden" || cs.display === "none") return false;
  return (
    r.top < (window.innerHeight || 0) &&
    r.bottom > 0 &&
    r.left < (window.innerWidth || 0) &&
    r.right > 0
  );
}

async function activateFromDeck(base: string) {
  if (auth.status.value !== "signed-in") return;
  if (!props.questionId || !props.pollId) return;
  if (lastSentIntent === "open") return;
  const token = auth.state.value.token;
  if (!token) return;
  const url = `${base.replace(/\/$/, "")}/api/deck/polls/${encodeURIComponent(props.pollId)}/activate`;
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Deck-Token": token
      },
      body: JSON.stringify({ questionId: props.questionId })
    });
    if (res.status === 401) {
      try {
        const body = (await res.clone().json()) as { code?: string };
        if (body.code === "DECK_TOKEN_INVALID") auth.markRevoked();
      } catch {
        /* noop */
      }
    } else if (res.ok) {
      lastSentIntent = "open";
    }
  } catch {
    /* noop — paused indicator covers this */
  }
}

async function closeFromDeck(base: string) {
  if (auth.status.value !== "signed-in") return;
  if (!props.pollId) return;
  // Only close what this panel opened. A panel that never activated has nothing
  // to close — and racing closes from off-screen panels would clobber whichever
  // panel just opened the question.
  if (lastSentIntent !== "open") return;
  if (!props.questionId) return;
  const token = auth.state.value.token;
  if (!token) return;
  const url = `${base.replace(/\/$/, "")}/api/deck/polls/${encodeURIComponent(props.pollId)}/close`;
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Deck-Token": token
      },
      // Body scopes the close to this panel's question — backend no-ops if a
      // different question is active. Prevents a slow slide-leave close from
      // landing after the next slide's activate and closing the wrong question.
      body: JSON.stringify({ questionId: props.questionId }),
      // keepalive lets the request survive navigation / unmount.
      keepalive: true
    });
    if (res.ok) {
      lastSentIntent = "closed";
    }
  } catch {
    /* noop — best-effort */
  }
}

let visibilityObserver: IntersectionObserver | null = null;

onMounted(async () => {
  // Resolve the backend URL in this priority order:
  //   1. explicit `server="..."` attribute on the tag (per-slide override)
  //   2. `pollServer:` on the slide's frontmatter or the deck headmatter
  //   3. URL set via configureDeckAuthBackend() (legacy data.ts side-effect)
  // All three eventually drive the same auth + SSE + activate routing.
  const base = (props.server ?? "") || headmatterServer || getConfiguredBackend();
  // Anonymous viewers (and signed-in panels pinned to a CLOSED question) never receive a
  // matching SSE snapshot, so the panel would render "Waiting…" indefinitely even though the
  // question has tallied votes. Fetch the historical, question-scoped snapshot up front so the
  // panel shows existing data immediately. Only apply if a live snapshot hasn't already arrived
  // (SSE wins on race).
  if (props.questionId) {
    const histUrl = `${base.replace(/\/$/, "")}/api/polls/${encodeURIComponent(props.slug)}/questions/${encodeURIComponent(props.questionId)}/snapshot`;
    void fetch(histUrl)
      .then(async (res) => {
        if (!res.ok || snapshot.value) return;
        const ev = (await res.json()) as SnapshotEvent;
        if (snapshot.value) return;
        snapshot.value = ev;
        setPollResults(resultsKey.value, ev);
      })
      .catch(() => {
        /* noop — paused indicator / SSE handle live state */
      });
  }
  await activateFromDeck(base);
  watch(
    () => auth.status.value,
    (s) => {
      if (s === "signed-in") void activateFromDeck(base);
    }
  );
  if (root.value && typeof IntersectionObserver !== "undefined") {
    visibilityObserver = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting && e.intersectionRatio >= 0.5) {
            observerOpened = true;
            void activateFromDeck(base);
          } else if (observerOpened && e.intersectionRatio < 0.1) {
            // Slide left the viewport (and was previously visible): close so attendees
            // see "waiting" until it returns. Re-entry triggers the open branch above.
            void closeFromDeck(base);
          }
        }
      },
      { threshold: [0, 0.1, 0.5, 0.95] }
    );
    visibilityObserver.observe(root.value);
  }
  stop = openPollStream(base, props.slug, {
    onSnapshot: (ev: SnapshotEvent) => {
      if (props.questionId && ev.activeQuestion?.id !== props.questionId) {
        paused.value = false;
        if (root.value && isElementVisible(root.value)) {
          void activateFromDeck(base);
        }
        return;
      }
      snapshot.value = ev;
      paused.value = false;
      closedNotice.value = null;
      setPollResults(resultsKey.value, ev);
    },
    onTally: (ev: TallyDeltaEvent) => {
      if (!snapshot.value || snapshot.value.activeQuestion?.id !== ev.questionId) return;
      const entry = snapshot.value.tally.find((t) => t.optionId === ev.optionId);
      if (entry) entry.count = ev.count;
      else snapshot.value.tally.push({ optionId: ev.optionId, count: ev.count });
      setPollResults(resultsKey.value, snapshot.value);
    },
    onQuestionClosed: (ev: QuestionClosedEvent) => {
      if (snapshot.value && snapshot.value.activeQuestion?.id === ev.questionId) {
        closedNotice.value = snapshot.value.activeQuestion.prompt;
        // Wipe local panel state so it shows the "closed" notice, but leave
        // the shared store's snapshot intact. Aggregator slides
        // (usePollResults) need the last-known activeQuestion + tally to
        // render combined results after individual slides leave.
        snapshot.value = { ...snapshot.value, activeQuestion: null, tally: [] };
      }
    },
    onConnectionStateChange: (state) => {
      paused.value = state === "paused";
    }
  });
});

onUnmounted(() => {
  const base = (props.server ?? "") || headmatterServer || getConfiguredBackend();
  void closeFromDeck(base);
  stop?.();
  visibilityObserver?.disconnect();
});
</script>

<template>
  <div
    ref="root"
    class="sp-pollpanel"
    data-testid="poll-results"
    :data-theme="theme.mode"
    :style="{ width: panelWidth }"
  >
    <PollQrButton v-if="auth.status.value === 'signed-in'" :voter-url="voterUrl" />
    <div v-if="paused" class="sp-pollpanel__paused" data-testid="poll-paused">
      live updates paused
    </div>
    <ResultsPanel
      v-if="panelQuestion"
      :question="panelQuestion"
      :mode="theme.scrim === 'none' ? 'flat' : theme.scrim"
    />
    <p v-else-if="closedNotice" class="sp-pollpanel__waiting" data-testid="poll-waiting">
      Question closed. Waiting for the next one…
    </p>
    <p v-else class="sp-pollpanel__waiting" data-testid="poll-waiting">
      Waiting for the next question…
    </p>
  </div>
</template>

<style scoped>
.sp-pollpanel {
  position: relative;
  /* Width is set inline (style binding) to 85% of slidev's slide canvas,
     since `layout: center` makes a `width: %` resolve against intrinsic
     content width and collapse the panel. Margin auto centres it within
     the slide. */
  max-width: 100%;
  margin: 0 auto;
  font-family: var(--sp-font-sans);
}
.sp-pollpanel__paused {
  background: var(--sp-danger-bg);
  color: var(--sp-danger-fg);
  border: 1px solid var(--sp-danger);
  border-radius: var(--sp-radius-sm);
  padding: 6px 10px;
  font-size: 12px;
  margin-bottom: 8px;
}
.sp-pollpanel__waiting {
  color: var(--sp-fg-subtle);
  margin: 0;
  font-size: 13px;
  text-align: center;
  padding: 16px;
}
</style>
