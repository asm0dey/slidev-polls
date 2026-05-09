<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, useAttrs, watch } from "vue";
import {
  openPollStream,
  ResultsPanel,
  type QuestionClosedEvent,
  type SnapshotEvent,
  type TallyDeltaEvent
} from "@polls/shared";
import { useDeckAuth } from "../composables/useDeckAuth";
import { useSlidevTheme } from "../composables/useSlidevTheme";
import { getConfiguredBackend } from "../composables/configureDeckAuthBackend";

const props = defineProps<{
  slug: string;
  server?: string;
  questionId?: string;
  pollId?: string;
}>();

const auth = useDeckAuth();

const attrs = useAttrs();
const isDev =
  (import.meta as unknown as { env?: { DEV?: boolean } }).env?.DEV === true;
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

const panelQuestion = computed(() => {
  const s = snapshot.value;
  if (!s?.activeQuestion) return null;
  return {
    prompt: s.activeQuestion.prompt,
    options: s.activeQuestion.options.map(o => ({ id: o.id, label: o.label })),
    tally: s.tally
  };
});

function isElementVisible(el: HTMLElement): boolean {
  const r = el.getBoundingClientRect();
  if (r.width === 0 || r.height === 0) return false;
  const cs = getComputedStyle(el);
  if (cs.visibility === "hidden" || cs.display === "none") return false;
  return r.top < (window.innerHeight || 0) && r.bottom > 0
      && r.left < (window.innerWidth || 0) && r.right > 0;
}

async function activateFromDeck(base: string) {
  if (auth.status.value !== "signed-in") return;
  if (!props.questionId || !props.pollId) return;
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
    }
  } catch {
    /* noop — paused indicator covers this */
  }
}

let visibilityObserver: IntersectionObserver | null = null;

onMounted(async () => {
  // Prefer the explicit prop, then fall back to the URL the operator already
  // set via configureDeckAuthBackend(). That keeps slides clean (no per-slide
  // server="..." attribute) while still letting cross-origin decks point at
  // the right backend host.
  const base = (props.server ?? "") || getConfiguredBackend();
  await activateFromDeck(base);
  watch(
    () => auth.status.value,
    (s) => {
      if (s === "signed-in") void activateFromDeck(base);
    }
  );
  if (root.value && typeof IntersectionObserver !== "undefined") {
    visibilityObserver = new IntersectionObserver(entries => {
      for (const e of entries) {
        if (e.isIntersecting && e.intersectionRatio > 0.5) {
          void activateFromDeck(base);
        }
      }
    }, { threshold: [0.5, 0.95] });
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
    },
    onTally: (ev: TallyDeltaEvent) => {
      if (!snapshot.value || snapshot.value.activeQuestion?.id !== ev.questionId) return;
      const entry = snapshot.value.tally.find(t => t.optionId === ev.optionId);
      if (entry) entry.count = ev.count;
      else snapshot.value.tally.push({ optionId: ev.optionId, count: ev.count });
    },
    onQuestionClosed: (ev: QuestionClosedEvent) => {
      if (snapshot.value && snapshot.value.activeQuestion?.id === ev.questionId) {
        closedNotice.value = snapshot.value.activeQuestion.prompt;
        snapshot.value = { ...snapshot.value, activeQuestion: null, tally: [] };
      }
    },
    onConnectionStateChange: state => {
      paused.value = state === "paused";
    }
  });
});

onUnmounted(() => {
  stop?.();
  visibilityObserver?.disconnect();
});
</script>

<template>
  <div ref="root" class="sp-pollpanel" data-testid="poll-results" :data-theme="theme.mode">
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
  width: 80vw;
  max-width: 1200px;
  min-width: min(85vw, 700px);
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
