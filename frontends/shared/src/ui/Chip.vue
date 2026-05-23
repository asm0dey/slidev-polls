<script setup lang="ts">
defineProps<{
  label: string;
  invalid?: boolean;
  mono?: boolean;
}>();
defineEmits<{ remove: [] }>();
</script>

<template>
  <span class="sp-chip" :data-invalid="invalid || undefined" :data-mono="mono || undefined">
    <slot name="leading" />
    <span class="sp-chip__label">{{ label }}</span>
    <button class="sp-chip__remove" :aria-label="`Remove ${label}`" @click="$emit('remove')">
      ×
    </button>
  </span>
</template>

<style scoped>
.sp-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 4px 4px 10px;
  background: var(--sp-bg-muted);
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius-sm);
  font-size: 12px;
  font-family: var(--sp-font-sans), sans-serif;
  color: var(--sp-fg);
}
.sp-chip[data-mono] {
  font-family: var(--sp-font-mono), monospace;
}
.sp-chip[data-invalid] {
  background: var(--sp-danger-bg);
  border-color: var(--sp-danger);
  color: var(--sp-danger-fg);
}
.sp-chip__remove {
  border: 0;
  background: transparent;
  color: var(--sp-fg-faint);
  font-size: 14px;
  line-height: 1;
  padding: 2px 6px;
  cursor: pointer;
}
.sp-chip[data-invalid] .sp-chip__remove {
  color: var(--sp-danger-fg);
}
.sp-chip__remove:focus-visible {
  outline: 2px solid var(--sp-accent-ring);
  border-radius: 4px;
}
</style>
