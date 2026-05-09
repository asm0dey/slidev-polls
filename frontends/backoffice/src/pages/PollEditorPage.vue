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
import {
  AllowedOriginsField,
  Button,
  Input,
  Pill,
  IconChevronDown
} from "@polls/shared/ui";

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
const allowedOrigins = ref<string[]>([]);
const questions = reactive<DraftQuestion[]>(emptyDraft());

const loading = ref(props.mode === "edit");
const submitting = ref(false);
const formError = ref<string | null>(null);
const detail = ref<PollDetail | null>(null);

const expandedIndex = ref(0);

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
  allowedOrigins.value = d.allowedOrigins ?? [];
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
  if (allowedOrigins.value.length > 0) req.allowedOrigins = allowedOrigins.value.slice();
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
  req.allowedOrigins = allowedOrigins.value.slice();
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
  expandedIndex.value = questions.length - 1;
}

function removeQuestion(idx: number) {
  questions.splice(idx, 1);
  if (expandedIndex.value >= questions.length) {
    expandedIndex.value = Math.max(0, questions.length - 1);
  }
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
  <section class="pe" data-testid="poll-editor-page">
    <p v-if="loading" data-testid="poll-editor-loading">Loading…</p>

    <template v-else>
      <header class="pe__header">
        <div>
          <div class="pe__crumb">{{ mode === "create" ? "New poll" : "Poll" }}</div>
          <h1 class="pe__title">{{ title || "Untitled" }}</h1>
          <p v-if="detail" class="pe__sub">
            Code <code class="pe__code">{{ detail.slug }}</code> · {{ questions.length }} question{{ questions.length === 1 ? "" : "s" }}
          </p>
        </div>
        <div class="pe__header-actions">
          <router-link
            v-if="mode === 'edit' && pollId"
            :to="{ name: 'deck-tokens', params: { pollId } }"
            class="poll-editor__deck-tokens"
            data-testid="deck-tokens-link"
          >
            Deck tokens
          </router-link>
          <button
            v-if="mode === 'edit'"
            type="button"
            data-testid="poll-delete"
            class="pe__delete"
            @click="deletePoll"
          >
            Delete poll
          </button>
          <Button :disabled="!canSubmit" data-testid="poll-editor-submit" @click="onSubmit">
            {{ submitting ? "Saving…" : (mode === "create" ? "Create" : "Save changes") }}
          </Button>
        </div>
      </header>

      <!-- inline error after a submit failure -->
      <div v-if="formError" role="alert" data-testid="poll-form-error" class="pe__error">{{ formError }}</div>

      <details open class="pe__settings">
        <summary class="pe__summary">
          <span>Poll settings</span>
          <IconChevronDown />
        </summary>
        <div class="pe__settings-body">
          <div class="pe__field">
            <label class="pe__label">Title</label>
            <Input v-model="title" placeholder="Poll title" data-testid="poll-title" />
            <!-- keep legacy testid alias for test compatibility -->
            <input type="hidden" data-testid="poll-editor-title" :value="title" />
          </div>

          <div class="pe__field">
            <label class="pe__label">Slug</label>
            <SlugField v-model="slug" :mode="mode" @update:valid="(v: boolean) => slugValid = v" />
            <input type="hidden" data-testid="poll-slug" :value="slug" />
          </div>

          <div class="pe__field">
            <div class="pe__row">
              <label class="pe__label">Allowed origins (CORS)</label>
              <span class="pe__hint-inline">applies to entire poll</span>
            </div>
            <AllowedOriginsField v-model="allowedOrigins" />
            <p class="pe__hint">Browsers from these origins can vote &amp; subscribe to live results. Use <code>*</code> for any origin.</p>
          </div>
        </div>
      </details>

      <div class="pe__qhead">
        <span class="pe__qhead-label">Questions <span class="pe__qhead-count">· {{ questions.length }}</span></span>
        <Button size="sm" data-testid="add-question" @click="addQuestion">+ Add question</Button>
      </div>
      <div class="pe__qlist">
        <div
          v-for="(q, i) in questions"
          :key="(q.id ?? '') + ':' + i"
          class="pe__qrow"
          :data-active="q.status === 'ACTIVE' ? '' : undefined"
          :data-expanded="i === expandedIndex ? '' : undefined"
          :data-testid="`poll-editor-question-${i}`"
        >
          <!-- legacy testid for tests that use question-block -->
          <span style="display:none" data-testid="question-block" />
          <div class="pe__qhdr">
            <span class="pe__qhdr-meta">
              {{ i + 1 }} · multi-choice
              <Pill v-if="q.status" :tone="q.status === 'ACTIVE' ? 'success' : 'neutral'" :withDot="q.status === 'ACTIVE'">
                {{ q.status.toLowerCase() }}
              </Pill>
            </span>
            <div class="pe__qhdr-actions">
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
              <Button v-if="i !== expandedIndex" variant="ghost" size="sm" @click="expandedIndex = i">Edit</Button>
              <Button v-if="questions.length > 1" variant="ghost" size="sm" data-testid="question-remove" @click="removeQuestion(i)">×</Button>
            </div>
          </div>
          <template v-if="i === expandedIndex">
            <Input
              v-model="q.prompt"
              placeholder="Question prompt"
              data-testid="question-prompt"
            />
            <div class="pe__opts">
              <div v-for="(o, oi) in q.options" :key="oi" class="pe__opt" data-testid="option-row">
                <span class="pe__handle">⋮⋮</span>
                <Input v-model="o.label" data-testid="option-label" />
                <Button v-if="q.options.length > 2" variant="ghost" size="sm" data-testid="option-remove" @click="removeOption(i, oi)">×</Button>
              </div>
              <button class="pe__add-opt" type="button" data-testid="add-option" @click="addOption(i)">+ Add option</button>
            </div>
          </template>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.pe__header {
  display: flex; justify-content: space-between; align-items: flex-start;
  margin-bottom: 24px; gap: 16px;
}
.pe__crumb {
  font-size: 11px; letter-spacing: 0.08em; text-transform: uppercase;
  color: var(--sp-fg-subtle); font-weight: 500; margin-bottom: 4px;
}
.pe__title { font-size: 22px; font-weight: 600; letter-spacing: -0.02em; margin: 0; }
.pe__sub { font-size: 12px; color: var(--sp-fg-subtle); margin: 4px 0 0; }
.pe__code {
  font-family: var(--sp-font-mono); font-size: 11px;
  background: var(--sp-bg-muted); padding: 1px 6px; border-radius: 4px;
}
.pe__header-actions { display: flex; gap: 8px; align-items: center; }

.pe__delete {
  background: #fdecea;
  color: #b71c1c;
  border: 1px solid #f5c6cb;
  padding: 0.4rem 0.75rem;
  border-radius: 4px;
  cursor: pointer;
}

.pe__error {
  background: var(--sp-danger-bg, #fdecea); color: var(--sp-danger-fg, #b71c1c);
  border: 1px solid var(--sp-danger, #f5c6cb); border-radius: var(--sp-radius-sm, 4px);
  padding: 10px; font-size: 13px;
  margin-bottom: 16px;
}

.pe__settings { margin-bottom: 22px; }
.pe__summary {
  cursor: pointer; padding-bottom: 10px;
  border-bottom: 1px solid var(--sp-border);
  margin-bottom: 14px;
  display: flex; align-items: center; justify-content: space-between;
  list-style: none;
  font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em;
  font-weight: 600; color: var(--sp-fg);
}
.pe__summary::-webkit-details-marker { display: none; }
.pe__settings-body { display: flex; flex-direction: column; gap: 14px; }
.pe__field { display: flex; flex-direction: column; gap: 5px; }
.pe__row { display: flex; justify-content: space-between; align-items: baseline; }
.pe__label {
  font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em;
  color: var(--sp-fg-subtle); font-weight: 500;
}
.pe__hint-inline { font-size: 11px; color: var(--sp-fg-faint); }
.pe__hint { font-size: 11px; color: var(--sp-fg-subtle); margin: 0; }

.pe__qhead {
  display: flex; justify-content: space-between; align-items: center;
  margin: 28px 0 12px;
}
.pe__qhead-label {
  font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em;
  color: var(--sp-fg); font-weight: 600;
}
.pe__qhead-count { color: var(--sp-fg-faint); font-weight: 400; letter-spacing: 0; }

.pe__qlist { display: flex; flex-direction: column; gap: 8px; }
.pe__qrow {
  border: 1px solid var(--sp-border, #ddd);
  border-radius: var(--sp-radius-lg, 8px);
  padding: 12px 16px;
}
.pe__qrow[data-active] {
  border-color: var(--sp-accent);
  background: var(--sp-accent-soft);
}
.pe__qhdr { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 8px; }
.pe__qhdr-meta { font-size: 12px; color: var(--sp-fg-subtle); display: inline-flex; align-items: center; gap: 8px; }
.pe__qhdr-actions { display: flex; gap: 4px; align-items: center; }
.pe__opts { display: flex; flex-direction: column; gap: 5px; margin-top: 8px; }
.pe__opt { display: flex; gap: 6px; align-items: center; }
.pe__handle { color: var(--sp-fg-faint); font-size: 11px; width: 14px; cursor: grab; }
.pe__add-opt {
  padding: 8px; border: 1px dashed var(--sp-border-strong, #bbb);
  background: transparent; border-radius: var(--sp-radius-sm, 4px);
  font-size: 12px; color: var(--sp-fg-subtle); font-family: var(--sp-font-sans);
  cursor: pointer;
}
.pe__add-opt:hover { background: var(--sp-bg-muted); }
</style>
