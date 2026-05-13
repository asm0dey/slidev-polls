<script setup lang="ts">
import { ref, onBeforeUnmount } from "vue";

interface MenuItem {
  key: string;
  label: string;
  tone?: "neutral" | "danger";
}

defineProps<{
  label: string;
  items: MenuItem[];
}>();

const emit = defineEmits<{ (e: "select", key: string): void }>();

const open = ref(false);
const root = ref<HTMLElement | null>(null);

function toggle() {
  open.value = !open.value;
}

function pick(key: string) {
  open.value = false;
  emit("select", key);
}

function onDocClick(ev: MouseEvent) {
  if (!root.value) return;
  if (open.value && !root.value.contains(ev.target as Node)) open.value = false;
}

if (typeof document !== "undefined") {
  document.addEventListener("click", onDocClick);
  onBeforeUnmount(() => document.removeEventListener("click", onDocClick));
}
</script>

<template>
  <div ref="root" class="sp-menu">
    <button
      type="button"
      class="sp-menu__trigger"
      data-testid="menu-trigger"
      :aria-expanded="open"
      @click.stop="toggle"
    >
      {{ label }}
    </button>
    <ul v-if="open" class="sp-menu__items" data-testid="menu-items" role="menu">
      <li v-for="it in items" :key="it.key" role="none">
        <button
          type="button"
          :class="['sp-menu__item', it.tone === 'danger' ? 'sp-menu__item--danger' : '']"
          :data-testid="`menu-item-${it.key}`"
          role="menuitem"
          @click="pick(it.key)"
        >
          {{ it.label }}
        </button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.sp-menu {
  position: relative;
}
.sp-menu__trigger {
  background: transparent;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  padding: 7px 10px;
  font-size: 13px;
  color: var(--sp-fg);
  cursor: pointer;
}
.sp-menu__items {
  position: absolute;
  right: 0;
  top: calc(100% + 4px);
  min-width: 180px;
  list-style: none;
  margin: 0;
  padding: 4px;
  background: var(--sp-bg);
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
  z-index: 20;
}
.sp-menu__item {
  display: block;
  width: 100%;
  text-align: left;
  background: transparent;
  border: none;
  padding: 8px 10px;
  font-size: 13px;
  color: var(--sp-fg);
  cursor: pointer;
  border-radius: var(--sp-radius-sm);
}
.sp-menu__item:hover {
  background: var(--sp-bg-muted);
}
.sp-menu__item--danger {
  color: var(--sp-danger);
}
</style>
