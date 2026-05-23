<script setup lang="ts">
import { ref } from "vue";
import { defaultAdminClient, type AdminApiClient } from "../lib/admin-api";

const props = defineProps<{ apiClient?: Pick<AdminApiClient, "changePassword"> }>();
const api = props.apiClient ?? defaultAdminClient;

const currentPassword = ref("");
const newPassword = ref("");
const error = ref<string | null>(null);
const done = ref(false);

async function submit() {
  error.value = null;
  done.value = false;
  try {
    await api.changePassword(currentPassword.value, newPassword.value);
    done.value = true;
    currentPassword.value = "";
    newPassword.value = "";
  } catch (e: unknown) {
    const message = (e as { message?: string })?.message ?? "could not change password";
    error.value = message;
  }
}
</script>

<template>
  <form class="cpf" @submit.prevent="submit">
    <label class="cpf__field">
      <span class="cpf__label">Current password</span>
      <input
        name="currentPassword"
        type="password"
        class="cpf__input"
        v-model="currentPassword"
        autocomplete="current-password"
      />
    </label>
    <label class="cpf__field">
      <span class="cpf__label">New password (min 12 chars)</span>
      <input
        name="newPassword"
        type="password"
        class="cpf__input"
        v-model="newPassword"
        autocomplete="new-password"
        minlength="12"
      />
    </label>
    <button type="submit" class="cpf__submit">Change password</button>
    <p v-if="error" role="alert" class="cpf__error">{{ error }}</p>
    <p v-else-if="done" role="status" class="cpf__success">Password changed.</p>
  </form>
</template>

<style scoped>
.cpf {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.cpf__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.cpf__label {
  font-weight: 600;
  font-size: 0.875rem;
}
.cpf__input {
  padding: 0.4rem 0.5rem;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  background: var(--sp-bg);
  color: var(--sp-fg);
  color-scheme: inherit;
  font-size: 0.875rem;
}
.cpf__input:focus {
  outline: 2px solid var(--sp-accent-ring);
  outline-offset: 0;
  border-color: var(--sp-accent);
}
.cpf__submit {
  align-self: flex-start;
  padding: 0.4rem 0.9rem;
  background: var(--sp-accent);
  color: var(--sp-accent-fg, #fff);
  border: none;
  border-radius: var(--sp-radius);
  font-size: 0.875rem;
  cursor: pointer;
}
.cpf__submit:hover {
  opacity: 0.88;
}
.cpf__error {
  color: var(--sp-danger);
  font-size: 0.875rem;
  margin: 0;
}
.cpf__success {
  color: var(--sp-success-fg, #1b7a1b);
  font-size: 0.875rem;
  margin: 0;
}
</style>
