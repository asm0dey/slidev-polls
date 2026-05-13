<script setup lang="ts">
import { ref, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import type { AdminApiClient } from "../lib/admin-api";

const props = withDefaults(
  defineProps<{
    username: string;
    apiClient: AdminApiClient;
  }>(),
  {}
);

const router = useRouter();
const open = ref(false);
const root = ref<HTMLElement | null>(null);

function toggle() {
  open.value = !open.value;
}

async function logout() {
  open.value = false;
  try {
    await props.apiClient.logout();
  } catch (_err) {
    // Silent: clearing local state and pushing to /login is the right UX even if the server rejects.
  }
  await router.push({ name: "login" });
}

function onDocClick(ev: MouseEvent) {
  if (!root.value || !open.value) return;
  if (!root.value.contains(ev.target as Node)) open.value = false;
}

if (typeof document !== "undefined") {
  document.addEventListener("click", onDocClick);
  onBeforeUnmount(() => document.removeEventListener("click", onDocClick));
}
</script>

<template>
  <div ref="root" class="um">
    <button
      type="button"
      class="um__trigger"
      data-testid="user-menu-trigger"
      :aria-expanded="open"
      @click.stop="toggle"
    >
      {{ username }} ▾
    </button>
    <ul v-if="open" class="um__items" role="menu">
      <li role="none">
        <button
          type="button"
          class="um__item"
          role="menuitem"
          data-testid="user-menu-logout"
          @click="logout"
        >
          Sign out
        </button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.um {
  position: relative;
}
.um__trigger {
  width: 100%;
  text-align: left;
  background: transparent;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  padding: 8px 10px;
  font-size: 12px;
  color: var(--sp-fg);
  cursor: pointer;
}
.um__items {
  position: absolute;
  left: 0;
  bottom: calc(100% + 6px);
  width: 100%;
  list-style: none;
  margin: 0;
  padding: 4px;
  background: var(--sp-bg);
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
  z-index: 20;
}
.um__item {
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
.um__item:hover {
  background: var(--sp-bg-muted);
}
</style>
