<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import {
  ApiClient,
  ApiError,
  openPollStream,
  type PublicPollView,
  type SnapshotEvent,
  type TallyDeltaEvent,
  Button,
  IconCheck,
  LiveDot,
  ResultsPanel
} from "@polls/shared";
import {
  clearAlreadyVoted,
  hasAlreadyVoted,
  markAlreadyVoted
} from "../lib/voterFlag";

type ViewStatus = "loading" | "waiting" | "active" | "voted" | "error";

const props = withDefaults(
  defineProps<{
    slug: string;
    apiClient?: ApiClient;
    serverBase?: string;
  }>(),
  { apiClient: undefined, serverBase: "" }
);

const client = props.apiClient ?? new ApiClient();

const status = ref<ViewStatus>("loading");
const poll = ref<PublicPollView | null>(null);
const submitting = ref(false);
const errorMessage = ref<string | null>(null);
const selectedOptionId = ref<string | null>(null);
const liveTally = ref<{ optionId: string; count: number }[]>([]);
let stopStream: (() => void) | null = null;

async function load() {
  status.value = "loading";
  errorMessage.value = null;
  try {
    const view = await client.publicPoll(props.slug);
    poll.value = view;
    applyServerView(view);
  } catch (err) {
    status.value = "error";
    errorMessage.value = describeLoadError(err);
  }
}

function applyServerView(view: PublicPollView) {
  if (view.state === "ACTIVE" && view.activeQuestion) {
    const qid = view.activeQuestion.id;
    if (view.alreadyVoted || hasAlreadyVoted(props.slug, qid)) {
      markAlreadyVoted(props.slug, qid);
      status.value = "voted";
      ensureStream();
      return;
    }
    status.value = "active";
    return;
  }
  // No active question — drop any cached flags for this slug so the next
  // activate (possibly a re-opened earlier question) starts clean.
  clearAlreadyVoted(props.slug);
  status.value = "waiting";
}

function ensureStream() {
  if (stopStream) return;
  stopStream = openPollStream(props.serverBase, props.slug, {
    onSnapshot: (ev: SnapshotEvent) => {
      liveTally.value = ev.tally.slice();
    },
    onTally: (ev: TallyDeltaEvent) => {
      const entry = liveTally.value.find(t => t.optionId === ev.optionId);
      if (entry) entry.count = ev.count;
      else liveTally.value.push({ optionId: ev.optionId, count: ev.count });
    },
    onQuestionClosed: () => {
      // Server closed the question — keep the snapshot frozen until next load() resets state.
    },
    onConnectionStateChange: () => {
      // No paused indicator on the voter post-vote screen; keep last known tally.
    }
  });
}

async function submit() {
  if (!selectedOptionId.value || submitting.value) return;
  const qid = poll.value?.activeQuestion?.id;
  if (!qid) return;
  submitting.value = true;
  errorMessage.value = null;
  try {
    await client.submitVote(props.slug, {
      optionId: selectedOptionId.value,
      voterToken: ""
    });
    markAlreadyVoted(props.slug, qid);
    status.value = "voted";
    ensureStream();
  } catch (err) {
    if (err instanceof ApiError && err.code === "ALREADY_VOTED") {
      markAlreadyVoted(props.slug, qid);
      status.value = "voted";
      ensureStream();
      return;
    }
    if (err instanceof ApiError && err.code === "QUESTION_NOT_ACTIVE") {
      errorMessage.value =
        "That question was just closed by the presenter. Hang tight — another one may be coming.";
      await load();
      return;
    }
    errorMessage.value = describeSubmitError(err);
  } finally {
    submitting.value = false;
  }
}

function describeLoadError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 404) {
      return "No poll with that link. Double-check the slug and try again.";
    }
    return err.problem?.message ?? `Could not load the poll (HTTP ${err.status}).`;
  }
  if (err instanceof Error) return err.message;
  return "Could not load the poll.";
}

function describeSubmitError(err: unknown): string {
  if (err instanceof ApiError) {
    return err.problem?.message ?? `Vote rejected (HTTP ${err.status}).`;
  }
  if (err instanceof Error) return err.message;
  return "Vote could not be recorded.";
}

const resultsForPanel = computed(() => {
  const q = poll.value?.activeQuestion;
  if (!q) return { prompt: "", options: [], tally: [] };
  return {
    prompt: q.prompt,
    options: q.options.map(o => ({ id: o.id, label: o.label })),
    tally: liveTally.value
  };
});

const votedOptionLabel = computed(() => {
  const id = selectedOptionId.value;
  if (!id) return null;
  return poll.value?.activeQuestion?.options.find(o => o.id === id)?.label ?? null;
});

onMounted(load);
watch(() => props.slug, () => load());
onUnmounted(() => stopStream?.());
</script>

<template>
  <section class="pv" data-testid="poll-view">
    <p v-if="status === 'loading'" data-testid="poll-loading">Loading the poll…</p>

    <div v-else-if="status === 'error'" role="alert" data-testid="poll-error" class="pv__error">
      <p>{{ errorMessage }}</p>
    </div>

    <template v-else>
      <header class="pv__bar">
        <span class="pv__live">
          <LiveDot />
          {{ props.slug }} · live
        </span>
        <span v-if="poll?.activeQuestion" class="pv__counter">
          Q {{ poll.activeQuestion.ordinal }}
        </span>
      </header>

      <div v-if="status === 'waiting'" data-testid="poll-waiting">
        <h1 class="pv__title">{{ poll?.title }}</h1>
        <p class="pv__waiting-text">Waiting for the presenter to open a question.</p>
      </div>

      <div v-else-if="status === 'active' && poll?.activeQuestion" data-testid="poll-active">
        <div class="pv__eyebrow">Pick one</div>
        <h2 class="pv__prompt">{{ poll.activeQuestion.prompt }}</h2>
        <div class="pv__options">
          <button
            v-for="opt in poll.activeQuestion.options"
            :key="opt.id"
            type="button"
            class="pv__opt"
            :data-testid="`option-${opt.id}`"
            :data-selected="selectedOptionId === opt.id ? '' : undefined"
            :disabled="submitting"
            @click="selectedOptionId = opt.id"
          >
            <span>{{ opt.label }}</span>
            <IconCheck v-if="selectedOptionId === opt.id" />
          </button>
        </div>
        <Button
          class="pv__submit"
          :disabled="!selectedOptionId || submitting"
          data-testid="poll-submit"
          @click="submit"
        >
          {{ submitting ? "Submitting…" : "Submit answer" }}
        </Button>
        <p v-if="errorMessage" role="alert" class="pv__error" data-testid="poll-submit-error">
          {{ errorMessage }}
        </p>
      </div>

      <div v-else-if="status === 'voted' && poll?.activeQuestion" data-testid="poll-voted">
        <div class="pv__confirm">
          <IconCheck />
          <span>Answer recorded<span v-if="votedOptionLabel"> · {{ votedOptionLabel }}</span></span>
        </div>
        <h2 class="pv__prompt pv__prompt--small">{{ poll.activeQuestion.prompt }}</h2>
        <ResultsPanel :question="resultsForPanel" mode="flat" />
        <p class="pv__waiting-card">Waiting for next question…</p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.pv {
  background: var(--sp-bg);
  border-radius: var(--sp-radius-xl);
  border: 1px solid var(--sp-border);
  padding: 24px 20px;
  display: flex; flex-direction: column;
  min-height: 70vh;
}
.pv__bar {
  display: flex; justify-content: space-between; align-items: center;
  font-size: 11px; color: var(--sp-fg-subtle); margin-bottom: 24px;
}
.pv__live { display: flex; align-items: center; gap: 6px; }
.pv__counter { font-variant-numeric: tabular-nums; }
.pv__title { font-size: 20px; font-weight: 600; letter-spacing: -0.015em; margin: 0 0 8px; }
.pv__waiting-text { color: var(--sp-fg-subtle); margin: 0; }
.pv__eyebrow {
  font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase;
  color: var(--sp-fg-subtle); font-weight: 500; margin-bottom: 8px;
}
.pv__prompt {
  font-size: 20px; font-weight: 600;
  letter-spacing: -0.015em; line-height: 1.3; margin: 0 0 24px;
  color: var(--sp-fg);
}
.pv__prompt--small { font-size: 18px; margin-bottom: 18px; }
.pv__options { display: flex; flex-direction: column; gap: 8px; flex: 1; }
.pv__opt {
  text-align: left; background: var(--sp-bg);
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius-lg);
  padding: 14px 16px; font-size: 14px;
  font-family: var(--sp-font-sans); color: var(--sp-fg); cursor: pointer;
  display: flex; justify-content: space-between; align-items: center;
}
.pv__opt[data-selected] {
  background: var(--sp-fg); border-color: var(--sp-fg);
  color: var(--sp-bg); font-weight: 500;
}
.pv__opt:focus-visible { outline: 2px solid var(--sp-accent-ring); outline-offset: 2px; }
.pv__submit {
  margin-top: 18px; width: 100%;
  background: var(--sp-accent);
  color: var(--sp-accent-fg);
  border-color: var(--sp-accent);
}
.pv__error {
  background: var(--sp-danger-bg); color: var(--sp-danger-fg);
  border: 1px solid var(--sp-danger); border-radius: var(--sp-radius-sm);
  padding: 8px 10px; font-size: 12px; margin: 8px 0 0;
}
.pv__confirm {
  display: flex; align-items: center; gap: 8px;
  font-size: 12px; color: var(--sp-accent); font-weight: 500;
  margin-bottom: 6px;
}
.pv__waiting-card {
  margin: 14px 0 0; padding: 10px;
  background: var(--sp-bg-muted); border-radius: var(--sp-radius-sm);
  text-align: center; font-size: 11px; color: var(--sp-fg-subtle);
}
</style>
