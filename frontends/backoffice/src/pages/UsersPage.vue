<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  AdminApiClient,
  AdminApiError,
  defaultAdminClient,
  type AdminUserView
} from "../lib/admin-api";
import { Button, Input } from "@polls/shared/ui";

const props = withDefaults(
  defineProps<{ apiClient?: AdminApiClient }>(),
  { apiClient: undefined }
);
const client = props.apiClient ?? defaultAdminClient;

const users = ref<AdminUserView[]>([]);
const username = ref("");
const password = ref("");
const displayName = ref("");
const submitting = ref(false);
const errorMessage = ref<string | null>(null);

async function refresh() {
  users.value = await client.listUsers();
}

onMounted(() => {
  refresh();
});

async function onSubmit() {
  submitting.value = true;
  errorMessage.value = null;
  try {
    await client.createUser({
      username: username.value,
      password: password.value,
      displayName: displayName.value
    });
    username.value = "";
    password.value = "";
    displayName.value = "";
    await refresh();
  } catch (err) {
    errorMessage.value = describeError(err);
  } finally {
    submitting.value = false;
  }
}

function describeError(err: unknown): string {
  if (err instanceof AdminApiError) {
    const fieldErrors = err.problem?.errors;
    if (fieldErrors && Object.keys(fieldErrors).length > 0) {
      return Object.entries(fieldErrors)
        .map(([field, msgs]) => `${field}: ${msgs.join(", ")}`)
        .join("; ");
    }
    return err.problem?.message ?? `Request failed (HTTP ${err.status}).`;
  }
  if (err instanceof Error) return err.message;
  return "Failed to create user.";
}
</script>

<template>
  <section data-testid="users-page" class="users-page">
    <h1 class="users-page__title">Presenters</h1>
    <table class="users-page__table">
      <thead>
        <tr>
          <th>Username</th>
          <th>Display name</th>
          <th>Created</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="u in users" :key="u.username">
          <td>{{ u.username }}</td>
          <td>{{ u.displayName }}</td>
          <td>{{ new Date(u.createdAt).toLocaleString() }}</td>
        </tr>
      </tbody>
    </table>

    <h2 class="users-page__subtitle">Add a presenter</h2>
    <form data-testid="users-form" class="users-page__form" @submit.prevent="onSubmit">
      <Input v-model="username" placeholder="username" data-testid="users-username" />
      <Input v-model="displayName" placeholder="display name" data-testid="users-displayname" />
      <Input
        v-model="password"
        type="password"
        placeholder="password (12+ chars)"
        data-testid="users-password"
      />
      <Button type="submit" :disabled="submitting" data-testid="users-submit">
        {{ submitting ? "Creating…" : "Add presenter" }}
      </Button>
      <p
        v-if="errorMessage"
        role="alert"
        data-testid="users-error"
        class="users-page__error"
      >
        {{ errorMessage }}
      </p>
    </form>
  </section>
</template>

<style scoped>
.users-page { max-width: 720px; }
.users-page__title { font-size: 18px; font-weight: 600; margin: 0 0 14px; }
.users-page__subtitle { font-size: 14px; font-weight: 600; margin: 0 0 10px; }
.users-page__table { width: 100%; border-collapse: collapse; margin-bottom: 32px; }
.users-page__table th {
  text-align: left;
  font-size: 12px;
  color: var(--sp-fg-subtle);
  padding: 6px 8px;
}
.users-page__table tr {
  border-top: 1px solid var(--sp-border);
}
.users-page__table td {
  font-size: 13px;
  padding: 8px;
}
.users-page__form {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 320px;
}
.users-page__error {
  background: var(--sp-danger-bg);
  color: var(--sp-danger-fg);
  border: 1px solid var(--sp-danger);
  border-radius: var(--sp-radius-sm);
  padding: 8px 10px;
  font-size: 12px;
}
</style>
