<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, useAttrs } from "vue";
import {
  openPollStream,
  type QuestionClosedEvent,
  type SnapshotEvent,
  type TallyDeltaEvent
} from "@polls/shared";
import PollBar from "./PollBar.vue";
import PollHeader from "./PollHeader.vue";
import { useDeckAuth } from "../composables/useDeckAuth";

// Props:
//   - slug          → drives the SSE subscription URL.
//   - server        → optional base URL; defaults to same-origin.
//   - questionId    → the slide's question; drives the activation payload.
//   - pollId        → the poll id; drives the activation URL.
//
// Feature 002 removed the `deckToken` prop: the bearer now comes from the in-deck auth
// control via `useDeckAuth()`, and the deck token is never embedded in slide markdown
// again (plan.md §Constraints). A dev-only console.warn below catches slides still
// passing :deckToken so the author sees the regression immediately.
//
// Live-reliability (Principle IV): any failure of the activation or the SSE subscription
// surfaces a "live updates paused" badge and leaves the deck navigable. A 401
// DECK_TOKEN_INVALID on the activation POST flips the composable to "revoked" so the auth
// control reverts to the not-signed-in visual (FR-008).
const props = defineProps<{
  slug: string;
  server?: string;
  questionId?: string;
  pollId?: string;
}>();

const auth = useDeckAuth();

// Slides passing a stale :deckToken="..." land here as an untyped attr; warn in dev so the
// author notices the regression before a production run (research.md D-005).
const attrs = useAttrs();
const isDev = (import.meta as unknown as { env?: { DEV?: boolean } }).env?.DEV === true;
if (isDev && "deckToken" in attrs) {
  // eslint-disable-next-line no-console
  console.warn(
    "[slidev-polls] `deckToken` prop removed in 002; sign in via the in-deck auth control."
  );
}

const snapshot = ref<SnapshotEvent | null>(null);
const paused = ref(false);
const closedNotice = ref<string | null>(null);
let stop: (() => void) | null = null;

const total = computed(() => {
  if (!snapshot.value) return 0;
  return snapshot.value.tally.reduce((sum, entry) => sum + entry.count, 0);
});

async function activateFromDeck(base: string) {
  // FR-007: zero activation calls unless the composable is currently signed-in.
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
      // Classify by Problem envelope code (plan.md §Constraints). Only DECK_TOKEN_INVALID
      // triggers a local revocation flip; other 401 shapes are treated as transient (kept
      // signed-in so the next navigation re-tries) per Principle IV.
      try {
        const body = (await res.clone().json()) as { code?: string };
        if (body.code === "DECK_TOKEN_INVALID") {
          auth.markRevoked();
        }
      } catch {
        // No parseable body — do nothing; the next activation will re-try.
      }
    }
  } catch {
    // Transport failures are swallowed here (Principle IV) — the SSE stream still
    // delivers whatever state the backend already holds. The paused badge and the
    // auth control's `couldn't reach server` messages cover the user-facing surface.
  }
}

onMounted(async () => {
  const base = props.server ?? "";
  await activateFromDeck(base);
  stop = openPollStream(base, props.slug, {
    onSnapshot: (ev: SnapshotEvent) => {
      // @TS-144 — when the slide declares its own questionId, a snapshot for a different
      // active question (the presenter moved on elsewhere) MUST NOT swap the viewer's
      // local slide content. The slide stays focused on its own question; the SSE stream
      // keeps delivering tally updates for that questionId.
      if (props.questionId && ev.activeQuestion?.id !== props.questionId) {
        paused.value = false;
        return;
      }
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
