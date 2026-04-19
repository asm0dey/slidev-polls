<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import {
  openPollStream,
  type QuestionClosedEvent,
  type SnapshotEvent,
  type TallyDeltaEvent
} from "@polls/shared";
import PollBar from "./PollBar.vue";
import PollHeader from "./PollHeader.vue";

/**
 * Props:
 *   - {@code slug}     — the poll's slug; drives the SSE subscription URL.
 *   - {@code server}   — optional base URL; defaults to same-origin ("").
 *   - {@code questionId} / {@code deckToken} / {@code pollId} — when all three are supplied,
 *     mounting the component requests deck-driven activation of {@code questionId} via
 *     {@code POST /api/deck/polls/{pollId}/activate}.
 *
 * Live-reliability (Principle IV): any failure of the activation or the SSE subscription
 * surfaces a "live updates paused" badge and leaves the deck navigable. No error state can crash
 * the slide.
 */
const props = defineProps<{
  slug: string;
  server?: string;
  questionId?: string;
  deckToken?: string;
  pollId?: string;
}>();

const snapshot = ref<SnapshotEvent | null>(null);
const paused = ref(false);
const closedNotice = ref<string | null>(null);
let stop: (() => void) | null = null;

const total = computed(() => {
  if (!snapshot.value) return 0;
  return snapshot.value.tally.reduce((sum, entry) => sum + entry.count, 0);
});

async function activateFromDeck(base: string) {
  // All three props are required — partial activation surfaces just ignore the call rather than
  // crash the mount path (Principle IV).
  if (!props.questionId || !props.deckToken || !props.pollId) return;
  const url = `${base.replace(/\/$/, "")}/api/deck/polls/${encodeURIComponent(props.pollId)}/activate`;
  try {
    await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Deck-Token": props.deckToken
      },
      body: JSON.stringify({ questionId: props.questionId })
    });
  } catch {
    // Swallowed: the public stream will still deliver whatever state the backend already holds;
    // a transient activation error MUST NOT block the slide from rendering.
  }
}

onMounted(async () => {
  const base = props.server ?? "";
  await activateFromDeck(base);
  stop = openPollStream(base, props.slug, {
    onSnapshot: (ev: SnapshotEvent) => {
      snapshot.value = ev;
      paused.value = false;
      closedNotice.value = null;
    },
    onTally: (ev: TallyDeltaEvent) => {
      // @TS-032 — stray tallies whose questionId doesn't match the current snapshot MUST be
      // ignored; the client waits for the next snapshot instead.
      if (!snapshot.value || snapshot.value.activeQuestion?.id !== ev.questionId) return;
      const entry = snapshot.value.tally.find((t) => t.optionId === ev.optionId);
      if (entry) entry.count = ev.count;
      else snapshot.value.tally.push({ optionId: ev.optionId, count: ev.count });
    },
    onQuestionClosed: (ev: QuestionClosedEvent) => {
      // Render a soft "just closed" notice until the next snapshot arrives — the backend will
      // follow with a fresh snapshot the moment a new question becomes active.
      if (snapshot.value && snapshot.value.activeQuestion?.id === ev.questionId) {
        closedNotice.value = snapshot.value.activeQuestion.prompt;
        snapshot.value = { ...snapshot.value, activeQuestion: null, tally: [] };
      }
    },
    onConnectionStateChange: (state) => {
      paused.value = state === "paused";
    }
  });
});

onUnmounted(() => {
  stop?.();
});
</script>

<template>
  <section class="poll-results" data-testid="poll-results">
    <div v-if="paused" class="poll-results__paused" data-testid="poll-paused">
      live updates paused
    </div>
    <template v-if="snapshot?.activeQuestion">
      <PollHeader
        :prompt="snapshot.activeQuestion.prompt"
        :ordinal="snapshot.activeQuestion.ordinal"
        :total="total"
      />
      <ol class="poll-results__bars">
        <PollBar
          v-for="opt in snapshot.activeQuestion.options"
          :key="opt.id"
          :option-id="opt.id"
          :label="opt.label"
          :count="snapshot.tally.find((t) => t.optionId === opt.id)?.count ?? 0"
          :total="total"
        />
      </ol>
    </template>
    <p v-else-if="closedNotice" class="poll-results__waiting" data-testid="poll-waiting">
      Question closed. Waiting for the next one…
    </p>
    <p v-else class="poll-results__waiting" data-testid="poll-waiting">
      Waiting for the next question…
    </p>
  </section>
</template>

<style scoped>
.poll-results {
  font-family: system-ui, -apple-system, sans-serif;
  padding: 1rem;
  border-radius: 8px;
  background: #ffffff;
  color: #0e1622;
  max-width: 560px;
}
.poll-results__paused {
  background: #fff4d6;
  border: 1px solid #f0c76b;
  color: #7a5200;
  font-size: 0.85em;
  padding: 0.3rem 0.6rem;
  border-radius: 4px;
  margin-bottom: 0.75rem;
}
.poll-results__bars {
  list-style: none;
  margin: 0;
  padding: 0;
}
.poll-results__waiting {
  color: #555;
  margin: 0.5rem 0 0 0;
}
</style>
