<script setup lang="ts">
import { onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import type { Poll } from "@polls/shared";
import { AdminApiClient, AdminApiError, defaultAdminClient } from "../lib/admin-api";
import QrPreview from "../components/QrPreview.vue";

const props = withDefaults(
  defineProps<{
    apiClient?: AdminApiClient;
  }>(),
  { apiClient: undefined }
);

const client = props.apiClient ?? defaultAdminClient;

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

async function onDelete(p: Poll) {
  if (!window.confirm(`Delete "${p.title}"? This cannot be undone.`)) return;
  try {
    await client.deletePoll(p.id);
    if (polls.value) {
      polls.value = polls.value.filter((row) => row.id !== p.id);
    }
  } catch (err) {
    errorMessage.value = describeError(err);
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="poll-list">
    <header class="poll-list__header">
      <h2>Your polls</h2>
      <RouterLink to="/polls/new" class="poll-list__new-btn">New poll</RouterLink>
    </header>

    <p v-if="loading" data-testid="poll-list-loading">Loading polls…</p>

    <p
      v-else-if="errorMessage"
      data-testid="poll-list-error"
      role="alert"
      class="poll-list__error"
    >
      {{ errorMessage }}
    </p>

    <p v-else-if="polls && polls.length === 0" data-testid="poll-list-empty">
      You don't have any polls yet.
      <RouterLink to="/polls/new">Create the first one.</RouterLink>
    </p>

    <ul v-else-if="polls" class="poll-list__rows">
      <li v-for="poll in polls" :key="poll.id" data-testid="poll-row" class="poll-list__row">
        <div class="poll-list__row-main">
          <h3 class="poll-list__title">{{ poll.title }}</h3>
          <p class="poll-list__meta">
            <span class="poll-list__slug">/{{ poll.slug }}</span>
            <span class="poll-list__status">{{ poll.status }}</span>
          </p>
          <p class="poll-list__join">
            Join link:
            <a :href="poll.publicUrl" data-testid="poll-join-link" target="_blank" rel="noopener">
              {{ poll.publicUrl }}
            </a>
          </p>
        </div>
        <div class="poll-list__row-side">
          <QrPreview
            data-testid="poll-qr-img"
            :poll-id="poll.id"
            :slug="poll.slug"
            :size="120"
          />
          <div class="poll-list__actions">
            <RouterLink
              :to="{ name: 'poll-edit', params: { pollId: poll.id } }"
              data-testid="poll-edit"
            >
              Edit
            </RouterLink>
            <button type="button" data-testid="poll-delete" @click="onDelete(poll)">
              Delete
            </button>
          </div>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.poll-list__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}
.poll-list__new-btn {
  padding: 0.4rem 0.75rem;
  background: #2c7be5;
  color: white;
  border-radius: 4px;
  text-decoration: none;
}
.poll-list__error {
  background: #fdecea;
  color: #b71c1c;
  padding: 0.5rem 0.75rem;
  border-radius: 4px;
}
.poll-list__rows {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.poll-list__row {
  display: flex;
  gap: 1.5rem;
  padding: 1rem;
  border: 1px solid #eee;
  border-radius: 8px;
}
.poll-list__row-main {
  flex: 1;
}
.poll-list__title {
  margin: 0 0 0.25rem;
}
.poll-list__meta {
  margin: 0 0 0.5rem;
  display: flex;
  gap: 0.75rem;
  color: #555;
  font-size: 0.875rem;
}
.poll-list__slug {
  font-family: ui-monospace, monospace;
}
.poll-list__qr {
  display: block;
  border: 1px solid #ddd;
  border-radius: 4px;
}
.poll-list__actions {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
  justify-content: center;
}
</style>
