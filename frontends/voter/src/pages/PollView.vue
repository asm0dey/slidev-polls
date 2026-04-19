<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { ApiClient, ApiError } from "@polls/shared";
import type { PublicPollView } from "@polls/shared";
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
  }>(),
  { apiClient: undefined }
);

const client = props.apiClient ?? new ApiClient();

const status = ref<ViewStatus>("loading");
const poll = ref<PublicPollView | null>(null);
const submitting = ref(false);
const errorMessage = ref<string | null>(null);

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
    if (view.alreadyVoted) {
      // Server confirms a vote for the current active question — upgrade the local cache so a
      // reload stays consistent.
      markAlreadyVoted(props.slug);
      status.value = "voted";
      return;
    }
    if (hasAlreadyVoted(props.slug)) {
      // Cached "yes I voted" from this client — keep the confirmation UX stable under a flaky
      // network where the server lookup didn't populate alreadyVoted for this question yet.
      status.value = "voted";
      return;
    }
    status.value = "active";
    return;
  }
  // Server reports no active question — the cache belongs to a previous question and is no
  // longer meaningful for the next round of voting.
  clearAlreadyVoted(props.slug);
  status.value = "waiting";
}

async function submit(optionId: string) {
  if (submitting.value) return;
  submitting.value = true;
  errorMessage.value = null;
  try {
    await client.submitVote(props.slug, { optionId, voterToken: "" });
    markAlreadyVoted(props.slug);
    status.value = "voted";
  } catch (err) {
    if (err instanceof ApiError && err.code === "ALREADY_VOTED") {
      // Either a stale client state or a duplicate tab — treat as "done".
      markAlreadyVoted(props.slug);
      status.value = "voted";
      return;
    }
    if (err instanceof ApiError && err.code === "QUESTION_NOT_ACTIVE") {
      // The presenter closed the question between the render and the submit — Principle IV:
      // degrade visibly (actionable message) and refresh so the UI reflects the new state.
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

onMounted(load);
watch(
  () => props.slug,
  () => load()
);
</script>

<template>
  <section class="poll-view" data-testid="poll-view">
    <p v-if="status === 'loading'" data-testid="poll-loading">Loading the poll…</p>

    <div v-else-if="status === 'error'" role="alert" data-testid="poll-error">
      <p>{{ errorMessage }}</p>
    </div>

    <div v-else-if="status === 'waiting'" data-testid="poll-waiting">
      <h1>{{ poll?.title }}</h1>
      <p>
        Waiting for the presenter to open a question. This page will update on its own when they
        do.
      </p>
    </div>

    <div v-else-if="status === 'voted'" data-testid="poll-voted">
      <h1>{{ poll?.title }}</h1>
      <p>Thanks — your vote is in. Watch the presenter's screen for the live tally.</p>
    </div>

    <div v-else-if="status === 'active' && poll?.activeQuestion" data-testid="poll-active">
      <h1>{{ poll.title }}</h1>
      <h2>{{ poll.activeQuestion.prompt }}</h2>
      <ul class="poll-options">
        <li v-for="opt in poll.activeQuestion.options" :key="opt.id">
          <button
            type="button"
            :data-testid="`option-${opt.id}`"
            :disabled="submitting"
            @click="submit(opt.id)"
          >
            {{ opt.label }}
          </button>
        </li>
      </ul>
      <p v-if="errorMessage" role="alert" data-testid="poll-submit-error">
        {{ errorMessage }}
      </p>
    </div>
  </section>
</template>

<style scoped>
.poll-view {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.poll-options {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.poll-options button {
  width: 100%;
  padding: 0.75rem 1rem;
  font-size: 1rem;
  border: 1px solid #ccc;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
}
.poll-options button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
