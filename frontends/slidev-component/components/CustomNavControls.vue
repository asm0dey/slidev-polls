<script setup lang="ts">
import { computed, ref } from "vue";
import { useSlideContext } from "@slidev/client";
import DeckAuthControl from "./DeckAuthControl.vue";
import { useDeckAuth } from "../composables/useDeckAuth";
import { configureDeckAuthBackend } from "../composables/configureDeckAuthBackend";

// Slidev exposes parsed headmatter via $slidev.configs and per-slide frontmatter
// via $frontmatter. Read pollServer from either source so the in-deck auth flow
// hits the backend origin (typically cross-origin from the Slidev dev server),
// mirroring how PollPanel.vue resolves its backend URL. useSlideContext throws
// outside a slidev context (e.g. unit tests), so it is wrapped defensively.
const slideCtx = (() => {
  try {
    return useSlideContext();
  } catch {
    return null;
  }
})();
const headmatterServer = (() => {
  const fm = (slideCtx?.$frontmatter ?? {}) as Record<string, unknown>;
  if (typeof fm.pollServer === "string" && fm.pollServer.length > 0) return fm.pollServer;
  const cfgs = (slideCtx?.$slidev?.configs ?? {}) as Record<string, unknown>;
  if (typeof cfgs.pollServer === "string" && cfgs.pollServer.length > 0) return cfgs.pollServer;
  return "";
})();

// Prime the configured-backend module before the useDeckAuth() singleton makes
// any fetch — currentBase() reads getConfiguredBackend() dynamically, so this
// works whether CustomNavControls mounts before or after PollPanel. Skipping
// when headmatterServer is empty preserves the legacy data.ts side-effect path.
if (headmatterServer.length > 0) {
  configureDeckAuthBackend(headmatterServer);
}

const auth = useDeckAuth();
const open = ref(false);

const isSignedIn = computed(() => auth.status.value === "signed-in");
const deckLabel = computed(() => auth.state.value.label ?? "deck");
const triggerTitle = computed(() =>
  isSignedIn.value ? `signed in: ${deckLabel.value}` : "sign in"
);

function toggle() {
  open.value = !open.value;
}
</script>

<template>
  <div class="deck-auth-nav">
    <button
      type="button"
      class="slidev-icon-btn deck-auth-nav__trigger"
      :class="{ 'deck-auth-nav__trigger--signed-in': isSignedIn }"
      :title="triggerTitle"
      :aria-label="triggerTitle"
      data-testid="deck-auth-nav-trigger"
      @click="toggle"
    >
      <span v-if="isSignedIn" class="deck-auth-nav__pill">
        <span class="deck-auth-nav__icon i-carbon:user-avatar-filled" aria-hidden="true" />
        <span class="deck-auth-nav__label">{{ deckLabel }}</span>
      </span>
      <template v-else>
        <span class="deck-auth-nav__icon i-carbon:user" aria-hidden="true" />
        <span class="sr-only">sign in</span>
      </template>
    </button>
    <div v-if="open" class="deck-auth-nav__popover" data-testid="deck-auth-nav-popover">
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
  /* inherits .slidev-icon-btn sizing/hover from Slidev so it visually matches sibling
     toolbar icons (BUG: was a chunky text button). */
  gap: 0.35rem;
}
.deck-auth-nav__trigger--signed-in {
  padding: 0 0.4rem;
  width: auto;
}
.deck-auth-nav__icon {
  font-size: 1.1em;
}
.deck-auth-nav__pill {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.8rem;
  font-weight: 600;
}
.deck-auth-nav__label {
  max-width: 8rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.deck-auth-nav__popover {
  position: absolute;
  bottom: calc(100% + 0.35rem);
  right: 0;
  background: transparent;
  border: 0;
  border-radius: 0;
  padding: 0;
  box-shadow: 0 4px 12px rgba(14, 22, 34, 0.18);
  z-index: 9999;
  min-width: 16rem;
}
</style>
