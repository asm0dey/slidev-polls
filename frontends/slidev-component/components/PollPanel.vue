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
  type SnapshotEvent
} from "@slidev-polls/shared";
import { useDeckAuth } from "../composables/useDeckAuth";
import { findSlideRoot, useSlidevTheme } from "../composables/useSlidevTheme";
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
// Slidev renders the current slide (`slide` in play mode, `presenter` in the
// presenter window), the next-slide preview (`previewNext`) and the overview
// (`overview`) as separate live copies of every component. Only the main
// views may drive the active question: a `previewNext` copy holds the *next*
// slide's panel, and on every SSE snapshot it would "reclaim" activation for
// that not-yet-presented question, fighting the on-screen panel — the deck
// flaps and the presenter sees the active question stick until a refresh.
// Gate every activate/close POST on the render context, mirroring
// @slidev/client's own `['slide','presenter']` checks. An undefined context
// (non-slidev embed, or the unit-test harness) counts as main.
const renderContext = (slideCtx as { $renderContext?: { value?: string } } | null)?.$renderContext
  ?.value;
const isMainRenderContext =
  renderContext === undefined || renderContext === "slide" || renderContext === "presenter";

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
// When SSE stays paused and we can confirm the backend is up but rejects the
// browser's origin via CORS, surface an actionable hint pointing at the
// backoffice's Allowed origins control. corsHint holds the visible message;
// reconnectAttempts gates the probe so a single pause doesn't fire it.
const corsHint = ref<string | null>(null);
const reconnectAttempts = ref(0);
// Module-local guard so concurrent paused-state callbacks don't stack overlapping
// probes (each issues a fetch + opens DevTools network noise).
let corsProbing = false;
let stop: (() => void) | null = null;

async function probeCors(base: string) {
  // Skip if we already know it's CORS, or if there's nothing question-scoped
  // to probe. The historical-snapshot endpoint is the same one the panel
  // already hits on mount, so a CORS reject here matches what the SSE client
  // would have hit.
  if (corsHint.value) return;
  if (corsProbing) return;
  if (!props.questionId || !props.pollId) return;
  const probeUrl = `${base.replace(/\/$/, "")}/api/polls/${encodeURIComponent(props.slug)}/questions/${encodeURIComponent(props.questionId)}/snapshot`;
  corsProbing = true;
  try {
    await fetch(probeUrl);
    // Cross-origin fetch resolved — backend is reachable and CORS isn't the issue.
    // (Even a 5xx resolves; only a CORS reject / network error throws TypeError.)
    return;
  } catch (err) {
    if (!(err instanceof TypeError)) return;
    // Cross-origin fetch threw. Confirm we're actually online by probing
    // same-origin — if that also fails, the user is offline and a CORS hint
    // would be misleading.
    try {
      await fetch(window.location.href, { method: "HEAD" });
      corsHint.value = `Live updates blocked — backend rejected this origin. Add ${window.location.origin} to the poll's Allowed origins in the backoffice.`;
    } catch {
      /* both failed → offline, not a CORS problem */
    }
  } finally {
    corsProbing = false;
  }
}

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
    // Pass arity through so ResultsPanel can decide whether to render the
    // "{voters} voters · {selections} selections" footer (multi-choice only).
    minSelections: s.activeQuestion.minSelections,
    maxSelections: s.activeQuestion.maxSelections,
    options: s.activeQuestion.options.map((o) => ({ id: o.id, label: o.label })),
    tally: s.tally
  };
});
const panelVoterCount = computed(() => snapshot.value?.voterCount ?? 0);

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
  if (!isMainRenderContext) return;
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
  if (!isMainRenderContext) return;
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
let slideDisplayObserver: MutationObserver | null = null;

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
  // Do not fire activateFromDeck here. Slidev mounts every slide simultaneously,
  // so an eager mount-time activate would race across N panels — last write wins
  // server-side and voters watch the active question cycle through every poll
  // in the deck before settling on whichever panel finished its POST last.
  // IntersectionObserver below is the single source of truth for "this panel
  // is the one the presenter is looking at, activate now".
  watch(
    () => auth.status.value,
    (s) => {
      // Only the currently-visible panel should reclaim activation on sign-in.
      // Without the visibility gate, every poll panel in the deck races the POST
      // and whichever loses the race overwrites the active question the user is
      // actually looking at.
      if (s === "signed-in" && root.value && isElementVisible(root.value)) {
        void activateFromDeck(base);
      }
    }
  );
  const slidePage = root.value ? findSlideRoot(root.value) : null;
  if (slidePage) {
    // Slidev path: every slide stays in the DOM and the framework toggles the
    // current slide's `style="display: none"` off and the previous slide's on.
    // IntersectionObserver does not reliably deliver callbacks for elements
    // that become display:none (the spec leaves it implementation-defined and
    // Chromium does not fire one), so a slide that loses focus never produced
    // a close event under the IO branch — voters kept seeing the last poll
    // even after the presenter navigated past it. Watch the slide-page's
    // style attribute instead and drive activate/close off display transitions.
    let slideVisible = slidePage.style.display !== "none";
    if (slideVisible) {
      observerOpened = true;
      void activateFromDeck(base);
    }
    slideDisplayObserver = new MutationObserver(() => {
      const visible = slidePage.style.display !== "none";
      if (visible && !slideVisible) {
        slideVisible = true;
        observerOpened = true;
        void activateFromDeck(base);
      } else if (!visible && slideVisible) {
        slideVisible = false;
        if (observerOpened) void closeFromDeck(base);
      }
    });
    slideDisplayObserver.observe(slidePage, {
      attributes: true,
      attributeFilter: ["style"]
    });
  } else if (root.value && typeof IntersectionObserver !== "undefined") {
    // Non-Slidev embed (e.g., the panel mounted on a plain page). Fall back to
    // IntersectionObserver so the activate/close edges still fire on real
    // viewport visibility changes.
    visibilityObserver = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting && e.intersectionRatio >= 0.5) {
            observerOpened = true;
            void activateFromDeck(base);
          } else if (observerOpened && e.intersectionRatio < 0.1) {
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
      // A live snapshot proves the SSE channel is healthy — drop any CORS
      // hint that was raised during an earlier paused streak, and reset the
      // pause counter so a future single pause doesn't immediately re-probe.
      corsHint.value = null;
      reconnectAttempts.value = 0;
      setPollResults(resultsKey.value, ev);
    },
    onQuestionClosed: (ev: QuestionClosedEvent) => {
      if (snapshot.value && snapshot.value.activeQuestion?.id === ev.questionId) {
        // Keep the panel rendering the final results. The deck embeds these
        // panels so the presenter (and anyone reviewing an exported PDF) can
        // see what the audience voted; wiping back to "waiting" hid the data
        // the moment the presenter navigated past the slide, and the printed
        // deck ended up with every poll panel blank. The voter SPA still flips
        // to "waiting" on its own question-closed handler — different concern,
        // different component. Mark closed via closedNotice for the optional
        // styling hook; snapshot stays populated.
        closedNotice.value = snapshot.value.activeQuestion.prompt;
      }
    },
    onConnectionStateChange: (state) => {
      paused.value = state === "paused";
      if (state === "paused") {
        reconnectAttempts.value += 1;
        // First pause might just be a transient blip; only probe once we've
        // seen the reconnect-loop fire at least once. Past that, the SSE
        // client is in retry-territory and a CORS reject is a plausible cause.
        if (reconnectAttempts.value >= 2) {
          void probeCors(base);
        }
      }
    }
  });
});

onUnmounted(() => {
  const base = (props.server ?? "") || headmatterServer || getConfiguredBackend();
  void closeFromDeck(base);
  stop?.();
  visibilityObserver?.disconnect();
  slideDisplayObserver?.disconnect();
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
    <!-- Mount the QR in any main view so the audience window (not signed in)
         can receive the presenter's synced overlay; only show the trigger
         button to the signed-in presenter. Skip preview/overview copies so
         their fixed full-screen overlay can't cover the real view. -->
    <PollQrButton
      v-if="isMainRenderContext"
      :voter-url="voterUrl"
      :can-trigger="auth.status.value === 'signed-in'"
      :sync-key="resultsKey"
    />
    <div v-if="paused" class="sp-pollpanel__paused" data-testid="poll-paused">
      live updates paused
    </div>
    <div v-if="corsHint" class="sp-pollpanel__cors-hint" role="alert" data-testid="poll-cors-hint">
      {{ corsHint }}
    </div>
    <ResultsPanel
      v-if="panelQuestion"
      :question="panelQuestion"
      :voter-count="panelVoterCount"
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
.sp-pollpanel__cors-hint {
  background: var(--sp-danger-bg);
  color: var(--sp-danger-fg);
  border: 1px solid var(--sp-danger);
  border-radius: var(--sp-radius-sm);
  padding: 8px 12px;
  font-size: 13px;
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
