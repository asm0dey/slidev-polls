<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRouter } from "vue-router";
import type { Poll } from "@slidev-polls/shared";
import { Button, Input, Pill, pluralize } from "@slidev-polls/shared/ui";
import { ConfirmDialog } from "@slidev-polls/shared/ui";
import { AdminApiClient, AdminApiError, defaultAdminClient } from "../lib/admin-api";
import QrPopover from "../components/QrPopover.vue";

const props = withDefaults(
  defineProps<{
    apiClient?: AdminApiClient;
  }>(),
  { apiClient: undefined }
);

const client = props.apiClient ?? defaultAdminClient;
const router = useRouter();

const polls = ref<Poll[] | null>(null);
const loading = ref(true);
const errorMessage = ref<string | null>(null);

async function load() {
  loading.value = true;
  errorMessage.value = null;
  try {
    polls.value = await client.listPolls();
  } catch (err) {
    polls.value = null;
    errorMessage.value = describeError(err);
  } finally {
    loading.value = false;
  }
}

function describeError(err: unknown): string {
  if (err instanceof AdminApiError) {
    if (err.code === "AUTH_REQUIRED") {
      return "Authentication required — please sign in again.";
    }
    if (err.code === "FORBIDDEN") {
      return "You are not authorised to view these polls.";
    }
    return err.problem?.message ?? `Request failed (HTTP ${err.status}).`;
  }
  if (err instanceof Error) {
    return err.message;
  }
  return "Unable to load polls.";
}

const pendingDelete = ref<Poll | null>(null);

function askDelete(p: Poll) {
  pendingDelete.value = p;
}

async function confirmDelete() {
  const p = pendingDelete.value;
  pendingDelete.value = null;
  if (!p) return;
  try {
    await client.deletePoll(p.id);
    if (polls.value) {
      polls.value = polls.value.filter((row) => row.id !== p.id);
    }
  } catch (err) {
    errorMessage.value = describeError(err);
  }
}

async function cloneRow(p: Poll) {
  try {
    const created = await client.clonePoll(p.id);
    await router.push({ name: "poll-edit", params: { pollId: created.id } });
  } catch (err) {
    errorMessage.value = describeError(err);
  }
}

onMounted(() => {
  void load();
});

const filterText = ref("");
const filteredPolls = computed(() =>
  (polls.value ?? []).filter((p) => p.title.toLowerCase().includes(filterText.value.toLowerCase()))
);
const totalCount = computed(() => polls.value?.length ?? 0);
const activeCount = computed(() => (polls.value ?? []).filter((p) => p.status === "OPEN").length);

function statusTone(s: string): "neutral" | "success" | "danger" {
  if (s === "OPEN") return "success";
  return "neutral";
}
function statusLabel(s: string): string {
  if (s === "OPEN") return "live";
  if (s === "DRAFT") return "draft";
  return "closed";
}
</script>

<template>
  <section class="pl" data-testid="poll-list-page">
    <div class="pl__head">
      <div>
        <h1 class="pl__title">Polls</h1>
        <p class="pl__sub">{{ pluralize(totalCount, "poll") }} · {{ activeCount }} live now</p>
      </div>
      <div class="pl__actions">
        <Input v-model="filterText" placeholder="Search…" style="width: 200px" />
        <RouterLink to="/polls/new" data-testid="new-poll-link">
          <Button>+ New poll</Button>
        </RouterLink>
      </div>
    </div>

    <p v-if="loading" data-testid="poll-list-loading">Loading polls…</p>

    <p v-else-if="errorMessage" data-testid="poll-list-error" role="alert" class="pl__error">
      {{ errorMessage }}
    </p>

    <div v-else-if="polls && polls.length === 0" data-testid="poll-list-empty" class="pl__empty">
      <h2>No polls yet</h2>
      <p>Polls live next to your slides. Create one to share a join link with your audience.</p>
      <RouterLink to="/polls/new" data-testid="empty-create-cta">
        <Button>Create your first poll</Button>
      </RouterLink>
    </div>

    <div v-else-if="polls" class="pl__table">
      <div class="pl__row pl__row--head">
        <span>Question</span>
        <span>Status</span>
        <span>Join link</span>
        <span>Actions</span>
      </div>
      <div v-for="poll in filteredPolls" :key="poll.id" data-testid="poll-row" class="pl__row">
        <div class="pl__row-main">
          <span class="pl__name">{{ poll.title }}</span>
          <span class="pl__slug">/{{ poll.slug }}</span>
        </div>
        <span class="pl__status-cell">
          <Pill :tone="statusTone(poll.status)" :withDot="poll.status === 'OPEN'">
            {{ statusLabel(poll.status) }}
          </Pill>
        </span>
        <a
          :href="poll.publicUrl"
          data-testid="poll-join-link"
          class="pl__join"
          target="_blank"
          rel="noopener"
        >
          {{ poll.publicUrl }}
        </a>
        <div class="pl__row-side">
          <QrPopover :poll-id="poll.id" :slug="poll.slug" :size="160" />
          <div class="pl__row-actions">
            <RouterLink
              :to="{ name: 'poll-edit', params: { pollId: poll.id } }"
              class="btn-link"
              data-testid="poll-edit"
            >
              Edit
            </RouterLink>
            <Button variant="secondary" size="sm" data-testid="poll-clone" @click="cloneRow(poll)">
              Clone
            </Button>
            <Button variant="danger" size="sm" data-testid="poll-delete" @click="askDelete(poll)">
              Delete
            </Button>
          </div>
        </div>
      </div>

      <p v-if="filteredPolls.length === 0 && filterText" class="pl__filter-empty">
        No polls match — try a different search, or create a new one.
      </p>
    </div>

    <ConfirmDialog
      :open="!!pendingDelete"
      title="Delete this poll?"
      :body="
        pendingDelete
          ? `Permanently removes “${pendingDelete.title}” and every vote. Live voters will see a 404.`
          : ''
      "
      :require-typed="pendingDelete?.slug ?? ''"
      confirm-label="Delete poll"
      tone="danger"
      @confirm="confirmDelete"
      @cancel="pendingDelete = null"
    />
  </section>
</template>

<style scoped>
.pl__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 18px;
}
.pl__title {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
  margin: 0;
}
.pl__sub {
  font-size: 12px;
  color: var(--sp-fg-subtle);
  margin: 2px 0 0;
}
.pl__actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.pl__actions a {
  text-decoration: none;
}

.pl__table {
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius-lg);
  overflow: hidden;
}
.pl__row {
  display: grid;
  grid-template-columns: 1fr 110px 1fr 220px;
  padding: 12px 14px;
  font-size: 13px;
  align-items: center;
  border-bottom: 1px solid var(--sp-bg-subtle);
  color: var(--sp-fg);
}
.pl__row--head {
  background: var(--sp-bg-muted);
  border-bottom: 1px solid var(--sp-border);
  font-size: 11px;
  color: var(--sp-fg-subtle);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  font-weight: 500;
  padding: 10px 14px;
}
.pl__row:hover:not(.pl__row--head) {
  background: var(--sp-bg-muted);
}
.pl__row:last-child {
  border-bottom: 0;
}
.pl__row-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.pl__name {
  font-weight: 500;
}
.pl__slug {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  color: var(--sp-fg-subtle);
}
.pl__status-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.pl__join {
  font-size: 12px;
  color: var(--sp-fg-subtle);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pl__row-side {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.pl__row-actions {
  display: flex;
  gap: 8px;
}
.pl__error {
  background: var(--sp-danger-subtle, #fdecea);
  color: var(--sp-danger, #b71c1c);
  padding: 0.5rem 0.75rem;
  border-radius: var(--sp-radius-lg, 4px);
}
.pl__filter-empty {
  padding: 32px;
  text-align: center;
  color: var(--sp-fg-subtle);
  font-size: 13px;
}
.pl__empty {
  padding: 48px 24px;
  text-align: center;
  border: 1px dashed var(--sp-border);
  border-radius: var(--sp-radius-lg);
  color: var(--sp-fg-muted);
}
.pl__empty h2 {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 6px;
  color: var(--sp-fg);
}
.pl__empty p {
  font-size: 13px;
  margin: 0 0 16px;
}

.btn-link {
  display: inline-flex;
  align-items: center;
  padding: 7px 12px;
  font-size: 12px;
  font-weight: 500;
  border-radius: var(--sp-radius);
  border: 1px solid var(--sp-border);
  background: var(--sp-bg);
  color: var(--sp-fg);
  text-decoration: none;
  transition: background var(--sp-dur) var(--sp-ease);
}
.btn-link:hover {
  background: var(--sp-bg-muted);
}
</style>
