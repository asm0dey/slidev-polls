<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { AdminApiClient, AdminApiError, defaultAdminClient } from "../lib/admin-api";

const props = withDefaults(
  defineProps<{
    apiClient?: AdminApiClient;
  }>(),
  {}
);

const client = props.apiClient ?? defaultAdminClient;
const router = useRouter();

const username = ref("");
const password = ref("");
const submitting = ref(false);
const errorMessage = ref<string | null>(null);

async function onSubmit() {
  submitting.value = true;
  errorMessage.value = null;
  try {
    await client.login({ username: username.value, password: password.value });
    await router.push("/polls");
  } catch (err) {
    errorMessage.value = describeError(err);
  } finally {
    submitting.value = false;
  }
}

function describeError(err: unknown): string {
  if (err instanceof AdminApiError) {
    if (err.status === 401 || err.code === "AUTH_REQUIRED") {
      return "Sign-in failed: check your username and password.";
    }
    return err.problem?.message ?? `Request failed (HTTP ${err.status}).`;
  }
  if (err instanceof Error) {
    return err.message;
  }
  return "Sign-in failed.";
}
</script>

<template>
  <section class="login">
    <h2>Sign in</h2>
    <form data-testid="login-form" class="login__form" @submit.prevent="onSubmit">
      <label class="login__field">
        <span>Username</span>
        <input
          v-model="username"
          data-testid="login-username"
          type="text"
          autocomplete="username"
          required
        />
      </label>
      <label class="login__field">
        <span>Password</span>
        <input
          v-model="password"
          data-testid="login-password"
          type="password"
          autocomplete="current-password"
          required
        />
      </label>
      <button type="submit" :disabled="submitting">
        {{ submitting ? "Signing in…" : "Sign in" }}
      </button>
      <p v-if="errorMessage" data-testid="login-error" role="alert" class="login__error">
        {{ errorMessage }}
      </p>
    </form>
  </section>
</template>

<style scoped>
.login {
  max-width: 24rem;
}
.login__form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.login__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.login__field input {
  padding: 0.4rem 0.5rem;
  border: 1px solid #ccc;
  border-radius: 4px;
}
.login__error {
  background: #fdecea;
  color: #b71c1c;
  padding: 0.5rem 0.75rem;
  border-radius: 4px;
  margin: 0;
}
</style>
