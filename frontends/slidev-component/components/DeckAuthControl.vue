<script setup lang="ts">
import { computed, ref } from "vue";
import { useDeckAuth } from "../composables/useDeckAuth";

// Visible states per FR-001 / FR-002 / FR-014:
//   - "not signed in" → form with an input + submit button
//   - "checking…"    → pending visual while the reload-time verify or a fresh signIn is in flight
//   - signed-in pill → shows the mint-time label + sign-out affordance
// Three distinct status messages (FR-014) come from the composable's `message` ref:
//   credential not recognised / couldn't reach server / (null).

const auth = useDeckAuth();
const pasted = ref("");

const isAnonymous = computed(
  () => auth.status.value === "anonymous" || auth.status.value === "revoked"
);
const isPending = computed(() => auth.status.value === "signed-in-pending");
const isSignedIn = computed(() => auth.status.value === "signed-in");

async function submit() {
  const token = pasted.value;
  pasted.value = "";
  await auth.signIn(token);
}

function onSignOut() {
  auth.signOut();
}
</script>

<template>
  <div class="deck-auth-control" data-testid="deck-auth-control">
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
        v-model="pasted"
        type="password"
        autocomplete="off"
        spellcheck="false"
        placeholder="deck token"
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
  position: fixed;
  top: 0.5rem;
  right: 0.5rem;
  font-family: system-ui, -apple-system, sans-serif;
  font-size: 0.85rem;
  padding: 0.35rem 0.6rem;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid #d0d5da;
  border-radius: 4px;
  color: #0e1622;
  z-index: 9999;
}
.deck-auth-control__form {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.deck-auth-control__status {
  color: #555;
}
.deck-auth-control__input {
  font: inherit;
  padding: 0.2rem 0.4rem;
  border: 1px solid #c3cad1;
  border-radius: 3px;
}
.deck-auth-control__submit,
.deck-auth-control__signout {
  font: inherit;
  padding: 0.2rem 0.55rem;
  border: 1px solid #8996a3;
  background: #f4f6f8;
  border-radius: 3px;
  cursor: pointer;
}
.deck-auth-control__pill {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: #0e1622;
}
.deck-auth-control__label {
  font-weight: 600;
}
.deck-auth-control__pending {
  color: #555;
  font-style: italic;
}
.deck-auth-control__message {
  margin: 0.25rem 0 0 0;
  color: #7a1f1f;
  font-size: 0.8rem;
}
</style>
