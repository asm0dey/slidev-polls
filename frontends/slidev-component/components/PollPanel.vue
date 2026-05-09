<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, useAttrs } from "vue";
import {
  openPollStream,
  ResultsPanel,
  type QuestionClosedEvent,
  type SnapshotEvent,
  type TallyDeltaEvent
} from "@polls/shared";
import { useDeckAuth } from "../composables/useDeckAuth";
import { useSlidevTheme } from "../composables/useSlidevTheme";

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

onMounted(async () => {
  const base = props.server ?? "";
  await activateFromDeck(base);
  stop = openPollStream(base, props.slug, {
    onSnapshot: (ev: SnapshotEvent) => {
      if (props.questionId && ev.activeQuestion?.id !== props.questionId) {
        paused.value = false;
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

onUnmounted(() => stop?.());
</script>

<template>
  <div ref="root" class="sp-pollpanel" data-testid="poll-results">
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
  width: 85%;
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
