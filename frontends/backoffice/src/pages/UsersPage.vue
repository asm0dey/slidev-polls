<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  AdminApiClient,
  AdminApiError,
  defaultAdminClient,
  type AdminUserView
} from "../lib/admin-api";
import { Button, ConfirmDialog, Input, formatRelative } from "@slidev-polls/shared/ui";

const props = withDefaults(defineProps<{ apiClient?: AdminApiClient }>(), { apiClient: undefined });
const client = props.apiClient ?? defaultAdminClient;

const users = ref<AdminUserView[]>([]);
const username = ref("");
const password = ref("");
const submitting = ref(false);
const errorMessage = ref<string | null>(null);

// Current user identity (resolved on mount via getAccount)
const currentUsername = ref<string>("");
const isAdmin = ref(false);

// Per-row reset-password state: username → new password input value
const resetPasswordOpen = ref<Record<string, boolean>>({});
const resetPasswordValue = ref<Record<string, string>>({});

// Block confirmation dialog state
const pendingBlockUsername = ref<string | null>(null);

async function refresh() {
  users.value = await client.listUsers();
}

onMounted(async () => {
  const [accountResult] = await Promise.allSettled([client.getAccount(), refresh()]);
  if (accountResult.status === "fulfilled") {
    currentUsername.value = accountResult.value.username;
    isAdmin.value = accountResult.value.isAdmin;
  } else {
    errorMessage.value = describeError(accountResult.reason, "Failed to load account.");
  }
});

async function onSubmit() {
  submitting.value = true;
  errorMessage.value = null;
  try {
    await client.createUser({
      username: username.value,
      password: password.value
    });
    username.value = "";
    password.value = "";
    await refresh();
  } catch (err) {
    errorMessage.value = describeError(err, "Failed to create user.");
  } finally {
    submitting.value = false;
  }
}

function describeError(err: unknown, fallback = "Operation failed."): string {
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
  return fallback;
}

function showResetForm(u: string) {
  resetPasswordOpen.value = { ...resetPasswordOpen.value, [u]: true };
  if (!(u in resetPasswordValue.value)) {
    resetPasswordValue.value = { ...resetPasswordValue.value, [u]: "" };
  }
}

function askBlock(u: string) {
  pendingBlockUsername.value = u;
}

async function confirmBlock() {
  const u = pendingBlockUsername.value;
  pendingBlockUsername.value = null;
  if (!u) return;
  // Clear any open reset form for this user so stale state doesn't reappear
  resetPasswordOpen.value = { ...resetPasswordOpen.value, [u]: false };
  resetPasswordValue.value = { ...resetPasswordValue.value, [u]: "" };
  errorMessage.value = null;
  try {
    await client.blockUser(u);
    await refresh();
  } catch (err) {
    errorMessage.value = describeError(err, "Failed to block user.");
  }
}

async function onUnblock(u: string) {
  // Clear any open reset form for this user so stale state doesn't reappear
  resetPasswordOpen.value = { ...resetPasswordOpen.value, [u]: false };
  resetPasswordValue.value = { ...resetPasswordValue.value, [u]: "" };
  errorMessage.value = null;
  try {
    await client.unblockUser(u);
    await refresh();
  } catch (err) {
    errorMessage.value = describeError(err, "Failed to unblock user.");
  }
}

async function onResetPassword(u: string) {
  errorMessage.value = null;
  const newPassword = resetPasswordValue.value[u] ?? "";
  if (newPassword.length < 12) {
    errorMessage.value = "New password must be at least 12 characters.";
    return;
  }
  try {
    await client.resetUserPassword(u, newPassword);
    resetPasswordOpen.value = { ...resetPasswordOpen.value, [u]: false };
    resetPasswordValue.value = { ...resetPasswordValue.value, [u]: "" };
    await refresh();
  } catch (err) {
    errorMessage.value = describeError(err, "Failed to reset password.");
  }
}
</script>

<template>
  <section data-testid="users-page" class="users-page">
    <h1 class="users-page__title">Presenters</h1>
    <table class="users-page__table">
      <thead>
        <tr>
          <th>Username</th>
          <th>Created</th>
          <th v-if="isAdmin">Actions</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="u in users" :key="u.username">
          <td>
            <span>{{ u.username }}</span>
            <span
              v-if="u.blocked"
              :data-testid="`blocked-badge-${u.username}`"
              class="users-page__blocked-badge"
              >Blocked</span
            >
          </td>
          <td>{{ formatRelative(u.createdAt) }}</td>
          <td v-if="isAdmin && u.username !== currentUsername" class="users-page__actions">
            <Button
              :data-testid="`reset-password-${u.username}`"
              class="users-page__action-btn"
              @click="showResetForm(u.username)"
              >Reset password</Button
            >
            <Button
              v-if="!u.blocked"
              :data-testid="`block-${u.username}`"
              class="users-page__action-btn users-page__action-btn--danger"
              @click="askBlock(u.username)"
              >Block</Button
            >
            <Button
              v-else
              :data-testid="`unblock-${u.username}`"
              class="users-page__action-btn"
              @click="onUnblock(u.username)"
              >Unblock</Button
            >

            <div v-if="resetPasswordOpen[u.username]" class="users-page__reset-form">
              <Input
                :data-testid="`reset-password-input-${u.username}`"
                :model-value="resetPasswordValue[u.username] ?? ''"
                type="password"
                placeholder="new password (12+ chars)"
                minlength="12"
                @update:model-value="(v: string) => (resetPasswordValue[u.username] = v)"
              />
              <Button
                :data-testid="`reset-password-submit-${u.username}`"
                @click="onResetPassword(u.username)"
                >Set password</Button
              >
            </div>
          </td>
          <td v-else-if="isAdmin" />
        </tr>
      </tbody>
    </table>

    <p v-if="errorMessage" role="alert" data-testid="users-error" class="users-page__error">
      {{ errorMessage }}
    </p>

    <h2 class="users-page__subtitle">Add a presenter</h2>
    <form data-testid="users-form" class="users-page__form" @submit.prevent="onSubmit">
      <Input v-model="username" placeholder="username" data-testid="users-username" />
      <Input
        v-model="password"
        type="password"
        placeholder="password (12+ chars)"
        data-testid="users-password"
      />
      <Button type="submit" :disabled="submitting" data-testid="users-submit">
        {{ submitting ? "Creating…" : "Add presenter" }}
      </Button>
    </form>

    <ConfirmDialog
      :open="!!pendingBlockUsername"
      title="Block this user?"
      :body="
        pendingBlockUsername
          ? `Blocking “${pendingBlockUsername}” will immediately revoke all their active sessions.`
          : ''
      "
      confirm-label="Block user"
      tone="danger"
      @confirm="confirmBlock"
      @cancel="pendingBlockUsername = null"
    />
  </section>
</template>

<style scoped>
.users-page {
  max-width: 720px;
}
.users-page__title {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 14px;
}
.users-page__subtitle {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 10px;
}
.users-page__table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 24px;
}
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
.users-page__blocked-badge {
  display: inline-block;
  margin-left: 8px;
  font-size: 11px;
  font-weight: 600;
  color: var(--sp-danger-fg);
  background: var(--sp-danger-bg);
  border: 1px solid var(--sp-danger);
  border-radius: var(--sp-radius-sm);
  padding: 1px 6px;
  vertical-align: middle;
}
.users-page__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: flex-start;
}
.users-page__action-btn {
  font-size: 12px;
}
.users-page__action-btn--danger {
  color: var(--sp-danger-fg);
}
.users-page__reset-form {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  width: 100%;
  margin-top: 4px;
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
  margin-bottom: 24px;
}
</style>
