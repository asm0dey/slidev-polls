<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useDeckAuth } from "../composables/useDeckAuth";

// Visible states per FR-001 / FR-002 / FR-014:
//   - "not signed in" → form with login + password inputs + submit button (BUG-002: parity
//     with the admin UI credential flow — no opaque "deck token" paste field)
//   - "checking…"    → pending visual while the reload-time verify or a fresh signIn is in flight
//   - signed-in pill → shows the mint-time label + sign-out affordance
// Three distinct status messages (FR-014) come from the composable's `message` ref:
//   credential not recognised / couldn't reach server / (null).

const auth = useDeckAuth();
const username = ref("");
const password = ref("");

const root = ref<HTMLElement | null>(null);
const themeMode = ref<"light" | "dark">("light");

function detectTheme() {
  if (typeof window === "undefined" || !document.documentElement) return;
  if (document.documentElement.classList.contains("dark")) {
    themeMode.value = "dark";
    return;
  }
  if (document.documentElement.classList.contains("light")) {
    themeMode.value = "light";
    return;
  }
  const bg = getComputedStyle(document.body).backgroundColor;
  const m = bg.match(/rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/i);
  if (!m) return;
  const r = Number(m[1]), g = Number(m[2]), b = Number(m[3]);
  const lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  themeMode.value = lum > 0.6 ? "light" : "dark";
}

let observer: MutationObserver | null = null;
onMounted(() => {
  detectTheme();
  observer = new MutationObserver(detectTheme);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
});
onUnmounted(() => observer?.disconnect());

const isAnonymous = computed(
  () => auth.status.value === "anonymous" || auth.status.value === "revoked"
);
const isPending = computed(() => auth.status.value === "signed-in-pending");
const isSignedIn = computed(() => auth.status.value === "signed-in");

async function submit() {
  const u = username.value;
  const p = password.value;
  password.value = "";
  await auth.signInWithCredentials(u, p);
}

function onSignOut() {
  auth.signOut();
}
</script>

<template>
  <div ref="root" class="deck-auth-control" data-testid="deck-auth-control" :data-theme="themeMode">
    <div v-if="isSignedIn" class="deck-auth-control__pill">
      <span class="deck-auth-control__label">signed in: {{ auth.state.value.label ?? "deck" }}</span>
      <button
        type="button"
        data-testid="deck-auth-signout"
        class="deck-auth-control__signout"
        @click="onSignOut"
      >
        sign out
      </button>
    </div>
    <div v-else-if="isPending" class="deck-auth-control__pending">checking…</div>
    <form v-else class="deck-auth-control__form" @submit.prevent="submit">
      <span class="deck-auth-control__status">not signed in</span>
      <input
        v-model="username"
        type="text"
        autocomplete="username"
        spellcheck="false"
        placeholder="login"
        data-testid="deck-auth-username"
        class="deck-auth-control__input"
      />
      <input
        v-model="password"
        type="password"
        autocomplete="current-password"
        spellcheck="false"
        placeholder="password"
        data-testid="deck-auth-password"
        class="deck-auth-control__input"
      />
      <button type="submit" class="deck-auth-control__submit">sign in</button>
    </form>
    <p
      v-if="isAnonymous && auth.message.value"
      class="deck-auth-control__message"
      data-testid="deck-auth-message"
    >
      {{ auth.message.value }}
    </p>
  </div>
</template>

<style scoped>
.deck-auth-control {
  font-family: var(--sp-font-sans, system-ui, -apple-system, sans-serif);
  font-size: 12px;
  color: var(--sp-fg, #0a0a0a);
  background: var(--sp-bg, #ffffff);
  border: 1px solid var(--sp-border, #e5e5e5);
  border-radius: var(--sp-radius, 8px);
  padding: 10px 12px;
  min-width: 200px;
}
.deck-auth-control__form {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.deck-auth-control__status {
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--sp-fg-subtle, #737373);
  font-weight: 500;
  margin-bottom: 2px;
}
.deck-auth-control__input {
  font: inherit;
  width: 100%;
  box-sizing: border-box;
  padding: 7px 10px;
  border: 1px solid var(--sp-border, #e5e5e5);
  border-radius: var(--sp-radius-sm, 6px);
  background: var(--sp-bg, #ffffff);
  color: var(--sp-fg, #0a0a0a);
}
.deck-auth-control__input::placeholder {
  color: var(--sp-fg-faint, #a1a1aa);
}
.deck-auth-control__input:focus {
  outline: 2px solid var(--sp-accent-ring, rgba(124, 58, 237, 0.4));
  outline-offset: 0;
  border-color: var(--sp-accent, #7c3aed);
}
.deck-auth-control__submit {
  font: inherit;
  padding: 7px 10px;
  border: 0;
  background: var(--sp-accent, #7c3aed);
  color: var(--sp-accent-fg, #ffffff);
  border-radius: var(--sp-radius-sm, 6px);
  font-weight: 500;
  cursor: pointer;
}
.deck-auth-control__submit:hover {
  opacity: 0.92;
}
.deck-auth-control__signout {
  font: inherit;
  padding: 4px 8px;
  border: 1px solid var(--sp-border, #e5e5e5);
  background: var(--sp-bg, #ffffff);
  color: var(--sp-fg-muted, #52525b);
  border-radius: var(--sp-radius-sm, 6px);
  cursor: pointer;
  font-size: 11px;
}
.deck-auth-control__signout:hover {
  background: var(--sp-bg-muted, #fafafa);
}
.deck-auth-control__pill {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--sp-fg, #0a0a0a);
}
.deck-auth-control__label {
  font-weight: 600;
  font-size: 12px;
}
.deck-auth-control__pending {
  color: var(--sp-fg-subtle, #737373);
  font-style: italic;
  font-size: 12px;
}
.deck-auth-control__message {
  margin: 6px 0 0 0;
  padding: 6px 8px;
  background: var(--sp-danger-bg, #fef2f2);
  color: var(--sp-danger-fg, #991b1b);
  border: 1px solid var(--sp-danger, #dc2626);
  border-radius: var(--sp-radius-sm, 6px);
  font-size: 11px;
}
</style>
