<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: "primary" | "secondary" | "ghost" | "danger";
    size?: "sm" | "md";
    type?: "button" | "submit" | "reset";
  }>(),
  { variant: "primary", size: "md", type: "button" }
);
</script>

<template>
  <button :type="type" :data-variant="variant" :data-size="size" class="sp-btn">
    <slot />
  </button>
</template>

<style scoped>
.sp-btn {
  font-family: var(--sp-font-sans), sans-serif;
  font-weight: 500;
  border-radius: var(--sp-radius);
  border: 1px solid transparent;
  cursor: pointer;
  transition:
    background var(--sp-dur) var(--sp-ease),
    border-color var(--sp-dur) var(--sp-ease);
  white-space: nowrap;
}
.sp-btn[data-size="md"] {
  padding: 11px 14px;
  font-size: 14px;
}
.sp-btn[data-size="sm"] {
  padding: 7px 12px;
  font-size: 12px;
}

.sp-btn[data-variant="primary"] {
  background: var(--sp-fg);
  color: var(--sp-bg);
}
.sp-btn[data-variant="primary"]:hover {
  opacity: 0.9;
}

.sp-btn[data-variant="secondary"] {
  background: var(--sp-bg);
  color: var(--sp-fg);
  border-color: var(--sp-border);
}
.sp-btn[data-variant="secondary"]:hover {
  background: var(--sp-bg-muted);
}

.sp-btn[data-variant="ghost"] {
  background: transparent;
  color: var(--sp-fg-muted);
}
.sp-btn[data-variant="ghost"]:hover {
  background: var(--sp-bg-muted);
}

.sp-btn[data-variant="danger"] {
  background: var(--sp-danger);
  color: #fff;
}

.sp-btn:focus-visible {
  outline: 2px solid var(--sp-accent-ring);
  outline-offset: 2px;
}
.sp-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
