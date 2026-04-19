<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { openPollStream, type SnapshotEvent, type TallyDeltaEvent } from "@polls/shared";

const props = defineProps<{
  slug: string;
  server?: string;
  questionId?: string;
  deckToken?: string;
  pollId?: string;
}>();

const snapshot = ref<SnapshotEvent | null>(null);
const paused = ref(false);
let stop: (() => void) | null = null;

async function activateFromDeck(base: string) {
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
    // Activation failure must not crash the deck (FR-015, FR-018).
    // The viewer continues to subscribe to the public stream.
  }
}

onMounted(async () => {
  const base = props.server ?? "";
  await activateFromDeck(base);
  stop = openPollStream(base, props.slug, {
    onSnapshot: (ev) => { snapshot.value = ev; paused.value = false; },
    onTally: (ev: TallyDeltaEvent) => {
      if (!snapshot.value || snapshot.value.activeQuestion?.id !== ev.questionId) return;
      const entry = snapshot.value.tally.find((t) => t.optionId === ev.optionId);
      if (entry) entry.count = ev.count;
      else snapshot.value.tally.push({ optionId: ev.optionId, count: ev.count });
    },
    onConnectionStateChange: (s) => { paused.value = s === "paused"; }
  });
});

onUnmounted(() => { stop?.(); });
</script>

<template>
  <section class="poll-results">
    <div v-if="paused" class="paused-badge">live updates paused</div>
    <template v-if="snapshot?.activeQuestion">
      <h2>{{ snapshot.activeQuestion.prompt }}</h2>
      <ul>
        <li v-for="opt in snapshot.activeQuestion.options" :key="opt.id">
          <span>{{ opt.label }}</span>
          <strong>{{ snapshot.tally.find((t) => t.optionId === opt.id)?.count ?? 0 }}</strong>
        </li>
      </ul>
    </template>
    <p v-else>Waiting for the next question…</p>
  </section>
</template>
