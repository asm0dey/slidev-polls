<script setup lang="ts">
import { computed, ref } from "vue";
import DeckAuthControl from "./DeckAuthControl.vue";
import { useDeckAuth } from "../composables/useDeckAuth";

const auth = useDeckAuth();
const open = ref(false);

const isSignedIn = computed(() => auth.status.value === "signed-in");
const triggerLabel = computed(() =>
  isSignedIn.value ? (auth.state.value.label ?? "deck") : "sign in"
);
const triggerTitle = computed(() =>
  isSignedIn.value ? `signed in: ${auth.state.value.label ?? "deck"}` : "sign in"
);

function toggle() {
  open.value = !open.value;
}
</script>

<template>
  <div class="deck-auth-nav">
    <button
      type="button"
      class="deck-auth-nav__trigger"
      :class="{ 'deck-auth-nav__trigger--signed-in': isSignedIn }"
      :title="triggerTitle"
      data-testid="deck-auth-nav-trigger"
      @click="toggle"
    >
      {{ triggerLabel }}
    </button>
    <div
      v-if="open"
      class="deck-auth-nav__popover"
      data-testid="deck-auth-nav-popover"
    >
      <DeckAuthControl />
    </div>
  </div>
</template>

<style scoped>
.deck-auth-nav {
  position: relative;
  display: inline-flex;
  align-items: center;
}
.deck-auth-nav__trigger {
  font: inherit;
  font-size: 0.85rem;
  padding: 0.2rem 0.55rem;
  border: 1px solid #8996a3;
  background: #f4f6f8;
  color: #0e1622;
  border-radius: 3px;
  cursor: pointer;
}
.deck-auth-nav__trigger--signed-in {
  font-weight: 600;
}
.deck-auth-nav__popover {
  position: absolute;
  top: calc(100% + 0.35rem);
  right: 0;
  background: rgba(255, 255, 255, 0.98);
  border: 1px solid #d0d5da;
  border-radius: 4px;
  padding: 0.4rem 0.55rem;
  box-shadow: 0 4px 12px rgba(14, 22, 34, 0.12);
  z-index: 9999;
  min-width: 16rem;
}
</style>
