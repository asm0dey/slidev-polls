<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { AdminApiClient, AdminApiError, defaultAdminClient } from "../lib/admin-api";
import { Button, Input } from "@slidev-polls/shared/ui";

const props = withDefaults(defineProps<{ apiClient?: AdminApiClient }>(), { apiClient: undefined });
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
    await client.runSetup({
      username: username.value,
      password: password.value
    });
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
    // For VALIDATION_FAILED the per-field detail is what the user actually needs;
    // the top-level message is the generic "request body is invalid".
    const fieldErrors = err.problem?.errors;
    if (fieldErrors && Object.keys(fieldErrors).length > 0) {
      return Object.entries(fieldErrors)
        .map(([field, msgs]) => `${field}: ${msgs.join(", ")}`)
        .join("; ");
    }
    return err.problem?.message ?? `Request failed (HTTP ${err.status}).`;
  }
  if (err instanceof Error) return err.message;
  return "Setup failed.";
}
</script>

<template>
  <div class="login" data-testid="setup-page">
    <div class="login__form">
      <div class="login__brand">slidev polls</div>
      <h1 class="login__title">First-time setup</h1>
      <p class="login__sub">Create the first presenter account.</p>
      <form data-testid="setup-form" @submit.prevent="onSubmit">
        <Input v-model="username" type="text" placeholder="username" data-testid="setup-username" />
        <Input
          v-model="password"
          type="password"
          placeholder="password (12+ chars)"
          data-testid="setup-password"
          style="margin-top: 10px"
        />
        <Button
          type="submit"
          :disabled="submitting"
          style="margin-top: 12px; width: 100%"
          data-testid="setup-submit"
        >
          {{ submitting ? "Creating…" : "Create presenter →" }}
        </Button>
        <p v-if="errorMessage" role="alert" class="login__error" data-testid="setup-error">
          {{ errorMessage }}
        </p>
      </form>
    </div>
    <aside class="login__brandpanel">
      <div class="login__brandpanel-inner">
        <div class="login__eyebrow">Welcome</div>
        <p class="login__pitch">Set the credentials your audience will never see.</p>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 100vh;
  background: var(--sp-bg);
}
.login__form {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px;
}
.login__form > * {
  width: 320px;
  max-width: 100%;
}
.login__brand {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
  margin-bottom: 32px;
  color: var(--sp-fg);
}
.login__title {
  font-size: 22px;
  font-weight: 600;
  margin: 0 0 6px;
  letter-spacing: -0.02em;
  color: var(--sp-fg);
}
.login__sub {
  font-size: 13px;
  color: var(--sp-fg-subtle);
  margin: 0 0 24px;
}
.login__error {
  background: var(--sp-danger-bg);
  color: var(--sp-danger-fg);
  border: 1px solid var(--sp-danger);
  border-radius: var(--sp-radius-sm);
  padding: 8px 10px;
  font-size: 12px;
  margin-top: 10px;
}
.login__brandpanel {
  background: linear-gradient(135deg, #7c3aed 0%, #4338ca 100%);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
}
[data-theme="dark"] .login__brandpanel {
  background: radial-gradient(ellipse at top right, #7c3aed 0%, #4338ca 50%, #1e1b4b 100%);
}
.login__brandpanel-inner {
  max-width: 280px;
}
.login__eyebrow {
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  opacity: 0.7;
  margin-bottom: 10px;
}
.login__pitch {
  font-size: 14px;
  line-height: 1.5;
  margin: 0;
  opacity: 0.92;
}
@media (max-width: 768px) {
  .login {
    grid-template-columns: 1fr;
  }
  .login__brandpanel {
    display: none;
  }
}
</style>
