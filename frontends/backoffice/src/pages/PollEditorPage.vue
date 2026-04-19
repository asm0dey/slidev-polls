<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import type {
  CreateOptionRequest,
  CreatePollRequest,
  CreateQuestionRequest,
  PollDetail,
  UpdatePollRequest
} from "@polls/shared";
import {
  AdminApiClient,
  AdminApiError,
  defaultAdminClient
} from "../lib/admin-api";
import SlugField from "../components/SlugField.vue";
import { checkSlug } from "../lib/slug-rules";

type Mode = "create" | "edit";

interface DraftOption {
  id?: string;
  label: string;
}

interface DraftQuestion {
  id?: string;
  prompt: string;
  options: DraftOption[];
  status: "DRAFT" | "ACTIVE" | "CLOSED";
}

const props = withDefaults(
  defineProps<{
    mode: Mode;
    pollId?: string;
    apiClient?: AdminApiClient;
  }>(),
  { pollId: undefined, apiClient: undefined }
);

const client = props.apiClient ?? defaultAdminClient;
const router = useRouter();

const title = ref("");
const slug = ref("");
const slugValid = ref(true); // empty slug is valid in create mode (server derives)
const questions = reactive<DraftQuestion[]>(emptyDraft());

const loading = ref(props.mode === "edit");
const submitting = ref(false);
const formError = ref<string | null>(null);
const detail = ref<PollDetail | null>(null);

function emptyDraft(): DraftQuestion[] {
  return [
    {
      prompt: "",
      status: "DRAFT",
      options: [{ label: "" }, { label: "" }]
    }
  ];
}

function questionsFromDetail(d: PollDetail): DraftQuestion[] {
  return d.questions
    .slice()
    .sort((a, b) => a.ordinal - b.ordinal)
    .map((q): DraftQuestion => ({
      id: q.id,
      prompt: q.prompt,
      status: q.status,
      options: q.options
        .slice()
        .sort((a, b) => a.position - b.position)
        .map((o): DraftOption => ({ id: o.id, label: o.label }))
    }));
}

function loadFromDetail(d: PollDetail) {
  detail.value = d;
  title.value = d.title;
  slug.value = d.slug;
  questions.splice(0, questions.length, ...questionsFromDetail(d));
}

async function load() {
  if (props.mode !== "edit" || !props.pollId) return;
  loading.value = true;
  formError.value = null;
  try {
    const d = await client.getPoll(props.pollId);
    loadFromDetail(d);
  } catch (err) {
    formError.value = describeError(err);
  } finally {
    loading.value = false;
  }
}

function describeError(err: unknown): string {
  if (err instanceof AdminApiError) {
    if (err.code === "SLUG_TAKEN") {
      return "That slug is already in use by one of your polls.";
    }
    if (err.code === "SLUG_INVALID") {
      return "Slug format is invalid.";
    }
    if (err.code === "SLUG_RESERVED") {
      return "That slug is reserved. Pick a different one.";
    }
    if (err.code === "ACTIVATION_REJECTED") {
      return "Question can't be activated — make sure it has at least two options.";
    }
    if (err.code === "AUTH_REQUIRED") {
      return "Authentication required — please sign in again.";
    }
    if (err.code === "FORBIDDEN") {
      return "You don't own this poll.";
    }
    if (err.code === "NOT_FOUND") {
      return "Poll not found.";
    }
    return err.problem?.message ?? `Request failed (HTTP ${err.status}).`;
  }
  if (err instanceof Error) {
    return err.message;
  }
  return "Request failed.";
}

const slugIsAcceptable = computed(() => {
  if (props.mode === "create" && slug.value.length === 0) return true;
  return checkSlug(slug.value).valid;
});

const canSubmit = computed(() => {
  if (submitting.value) return false;
  if (title.value.trim().length === 0) return false;
  if (questions.length === 0) return false;
  if (!slugIsAcceptable.value) return false;
  for (const q of questions) {
    if (q.prompt.trim().length === 0) return false;
    if (q.options.length < 2) return false;
    for (const o of q.options) {
      if (o.label.trim().length === 0) return false;
    }
  }
  return true;
});

function buildCreateRequest(): CreatePollRequest {
  const req: CreatePollRequest = {
    title: title.value.trim(),
    questions: questions.map(toCreateQuestion)
  };
  if (slug.value.length > 0) req.slug = slug.value;
  return req;
}

function buildUpdateRequest(): UpdatePollRequest {
  const req: UpdatePollRequest = {
    title: title.value.trim()
  };
  if (slug.value.length > 0 && (!detail.value || slug.value !== detail.value.slug)) {
    req.slug = slug.value;
  }
  req.questions = questions.map(toCreateQuestion);
  return req;
}

function toCreateQuestion(q: DraftQuestion): CreateQuestionRequest {
  return {
    prompt: q.prompt.trim(),
    options: q.options.map((o): CreateOptionRequest => ({ label: o.label.trim() }))
  };
}

async function onSubmit() {
  if (!canSubmit.value) return;
  submitting.value = true;
  formError.value = null;
  try {
    if (props.mode === "create") {
      await client.createPoll(buildCreateRequest());
    } else if (props.pollId) {
      const updated = await client.updatePoll(props.pollId, buildUpdateRequest());
      loadFromDetail(updated);
    }
    await router.push("/polls");
  } catch (err) {
    formError.value = describeError(err);
  } finally {
    submitting.value = false;
  }
}

function addQuestion() {
  questions.push({
    prompt: "",
    status: "DRAFT",
    options: [{ label: "" }, { label: "" }]
  });
}

function removeQuestion(idx: number) {
  questions.splice(idx, 1);
}

function addOption(qIdx: number) {
  questions[qIdx].options.push({ label: "" });
}

function removeOption(qIdx: number, oIdx: number) {
  if (questions[qIdx].options.length <= 2) return;
  questions[qIdx].options.splice(oIdx, 1);
}

async function activate(q: DraftQuestion) {
  if (!props.pollId || !q.id) return;
  try {
    const updated = await client.activateQuestion(props.pollId, { questionId: q.id });
    loadFromDetail(updated);
  } catch (err) {
    formError.value = describeError(err);
  }
}

async function closeActive() {
  if (!props.pollId) return;
  try {
    const updated = await client.closeActiveQuestion(props.pollId);
    loadFromDetail(updated);
  } catch (err) {
    formError.value = describeError(err);
  }
}

async function deletePoll() {
  if (!props.pollId) return;
  if (!window.confirm(`Delete "${title.value}"? This cannot be undone.`)) return;
  try {
    await client.deletePoll(props.pollId);
    await router.push("/polls");
  } catch (err) {
    formError.value = describeError(err);
  }
}

watch(
  () => props.pollId,
  () => {
    void load();
  }
);

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="poll-editor">
    <header class="poll-editor__header">
      <h2>{{ mode === "create" ? "New poll" : "Edit poll" }}</h2>
      <div v-if="mode === 'edit'" class="poll-editor__header-actions">
        <router-link
          v-if="pollId"
          :to="{ name: 'deck-tokens', params: { pollId } }"
          class="poll-editor__deck-tokens"
          data-testid="deck-tokens-link"
        >
          Deck tokens
        </router-link>
        <button
          type="button"
          data-testid="poll-delete"
          class="poll-editor__delete"
          @click="deletePoll"
        >
          Delete poll
        </button>
      </div>
    </header>

    <p v-if="loading">Loading…</p>

    <form
      v-else
      data-testid="poll-form"
      class="poll-editor__form"
      @submit.prevent="onSubmit"
    >
      <label class="poll-editor__field">
        <span>Title</span>
        <input
          v-model="title"
          data-testid="poll-title"
          type="text"
          required
          maxlength="200"
        />
      </label>

      <SlugField
        v-model="slug"
        :label="mode === 'create' ? 'Slug (optional — derived from title if blank)' : 'Slug'"
        @update:valid="slugValid = $event"
      />
      <input
        type="hidden"
        data-testid="poll-slug"
        :value="slug"
      />

      <ul class="poll-editor__questions">
        <li
          v-for="(q, qIdx) in questions"
          :key="q.id ?? `new-${qIdx}`"
          data-testid="question-block"
          class="poll-editor__question"
        >
          <header class="poll-editor__question-header">
            <h3>Question {{ qIdx + 1 }} <small>({{ q.status }})</small></h3>
            <div class="poll-editor__question-actions">
              <button
                v-if="mode === 'edit' && q.id && q.status === 'DRAFT'"
                type="button"
                data-testid="question-activate"
                @click="activate(q)"
              >
                Activate
              </button>
              <button
                v-if="mode === 'edit' && q.id && q.status === 'ACTIVE'"
                type="button"
                data-testid="question-close"
                @click="closeActive"
              >
                Close
              </button>
              <button
                v-if="questions.length > 1"
                type="button"
                data-testid="question-remove"
                @click="removeQuestion(qIdx)"
              >
                Remove
              </button>
            </div>
          </header>
          <label class="poll-editor__field">
            <span>Prompt</span>
            <input
              v-model="q.prompt"
              data-testid="question-prompt"
              type="text"
              required
              maxlength="500"
            />
          </label>
          <ol class="poll-editor__options">
            <li
              v-for="(opt, oIdx) in q.options"
              :key="opt.id ?? `new-${qIdx}-${oIdx}`"
              data-testid="option-row"
              class="poll-editor__option"
            >
              <input
                v-model="opt.label"
                data-testid="option-label"
                type="text"
                required
                maxlength="200"
                :placeholder="`Option ${oIdx + 1}`"
              />
              <button
                v-if="q.options.length > 2"
                type="button"
                data-testid="option-remove"
                @click="removeOption(qIdx, oIdx)"
              >
                ×
              </button>
            </li>
          </ol>
          <button type="button" data-testid="add-option" @click="addOption(qIdx)">
            Add option
          </button>
        </li>
      </ul>

      <button type="button" data-testid="add-question" @click="addQuestion">
        Add question
      </button>

      <p
        v-if="formError"
        data-testid="poll-form-error"
        role="alert"
        class="poll-editor__error"
      >
        {{ formError }}
      </p>

      <div class="poll-editor__submit-row">
        <button type="submit" :disabled="!canSubmit">
          {{ submitting ? "Saving…" : mode === "create" ? "Create poll" : "Save changes" }}
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.poll-editor__header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.poll-editor__delete {
  background: #fdecea;
  color: #b71c1c;
  border: 1px solid #f5c6cb;
  padding: 0.4rem 0.75rem;
  border-radius: 4px;
}
.poll-editor__form {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.poll-editor__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.poll-editor__field input {
  padding: 0.4rem 0.5rem;
  border: 1px solid #ccc;
  border-radius: 4px;
}
.poll-editor__questions {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.poll-editor__question {
  border: 1px solid #ddd;
  border-radius: 6px;
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.poll-editor__question-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.poll-editor__question-actions {
  display: flex;
  gap: 0.5rem;
}
.poll-editor__options {
  list-style: none;
  padding-left: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.poll-editor__option {
  display: flex;
  gap: 0.5rem;
}
.poll-editor__option input {
  flex: 1;
  padding: 0.3rem 0.5rem;
  border: 1px solid #ccc;
  border-radius: 4px;
}
.poll-editor__error {
  background: #fdecea;
  color: #b71c1c;
  padding: 0.5rem 0.75rem;
  border-radius: 4px;
  margin: 0;
}
</style>
