<script setup lang="ts">
defineProps<{
  tone?: "neutral" | "success" | "danger";
  withDot?: boolean;
}>();
</script>

<template>
  <span class="sp-pill" :data-tone="tone ?? 'neutral'">
    <span v-if="withDot" class="sp-pill__dot" />
    <slot />
  </span>
</template>

<style scoped>
.sp-pill {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 2px 8px; border-radius: 999px;
  font-size: 11px; font-weight: 500; font-family: var(--sp-font-sans);
}
.sp-pill[data-tone="neutral"] { background: var(--sp-bg-subtle); color: var(--sp-fg-muted); }
.sp-pill[data-tone="success"] { background: var(--sp-success-bg); color: var(--sp-success-fg); }
.sp-pill[data-tone="danger"]  { background: var(--sp-danger-bg);  color: var(--sp-danger-fg); }
.sp-pill__dot {
  width: 5px; height: 5px; border-radius: 50%;
  background: currentColor;
}
.sp-pill[data-tone="success"] .sp-pill__dot { background: var(--sp-success); }
</style>
