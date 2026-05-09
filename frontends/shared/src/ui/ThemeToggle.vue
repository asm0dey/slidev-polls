<script setup lang="ts">
import { computed } from "vue";
import { useTheme } from "./useTheme";
import IconSun from "./IconSun.vue";
import IconMoon from "./IconMoon.vue";

const t = useTheme();
const ariaLabel = computed(() =>
  t.mode.value === "dark" ? "Switch to light mode" : "Switch to dark mode"
);
</script>

<template>
  <button class="sp-themetoggle" :aria-label="ariaLabel" @click="t.toggle">
    <IconMoon v-if="t.mode.value === 'light'" />
    <IconSun v-else />
  </button>
</template>

<style scoped>
.sp-themetoggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: transparent;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  cursor: pointer;
  color: var(--sp-fg-muted);
}
.sp-themetoggle:hover {
  background: var(--sp-bg-muted);
  color: var(--sp-fg);
}
.sp-themetoggle:focus-visible {
  outline: 2px solid var(--sp-accent-ring);
  outline-offset: 2px;
}
</style>
