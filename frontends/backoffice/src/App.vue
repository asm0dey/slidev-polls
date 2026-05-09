<script setup lang="ts">
import { computed } from "vue";
import { RouterLink, RouterView, useRoute } from "vue-router";
import { ThemeToggle } from "@slidev-polls/shared/ui";

const route = useRoute();
// Routes that render full-bleed (no sidebar/chrome). Both login and the
// first-run setup screen own the full viewport; sidebar links would either
// 401 (no session yet) or fight the centered form layout.
const isFullBleed = computed(() => route.name === "login" || route.name === "setup");
</script>

<template>
  <div v-if="isFullBleed" class="bo-login-host">
    <RouterView />
  </div>
  <div v-else class="bo-shell">
    <aside class="bo-sidebar">
      <div class="bo-brand">slidev polls</div>
      <nav class="bo-nav">
        <RouterLink to="/polls" class="bo-nav-item">Polls</RouterLink>
        <RouterLink to="/deck-tokens" class="bo-nav-item">Deck tokens</RouterLink>
        <RouterLink to="/users" class="bo-nav-item">Presenters</RouterLink>
      </nav>
      <div class="bo-sidebar-foot">
        <ThemeToggle />
      </div>
    </aside>
    <main class="bo-main">
      <RouterView />
    </main>
  </div>
</template>

<style>
body {
  margin: 0;
  background: var(--sp-bg);
  color: var(--sp-fg);
  font-family: var(--sp-font-sans);
}
</style>

<style scoped>
.bo-login-host { min-height: 100vh; }
.bo-shell { display: flex; min-height: 100vh; background: var(--sp-bg); }
.bo-sidebar {
  width: 200px; background: var(--sp-bg-muted);
  border-right: 1px solid var(--sp-border);
  padding: 18px 12px; display: flex; flex-direction: column;
}
.bo-brand {
  font-size: 13px; font-weight: 600; padding: 0 8px 16px;
  letter-spacing: -0.01em; color: var(--sp-fg);
}
.bo-nav { display: flex; flex-direction: column; gap: 2px; }
.bo-nav-item {
  padding: 7px 8px; border-radius: var(--sp-radius-sm);
  font-size: 13px; color: var(--sp-fg-muted); text-decoration: none;
}
.bo-nav-item:hover { background: var(--sp-bg-subtle); color: var(--sp-fg); }
.bo-nav-item.router-link-active {
  background: var(--sp-accent-soft); color: var(--sp-accent); font-weight: 500;
}
.bo-sidebar-foot { margin-top: auto; padding: 10px 8px; }
.bo-main { flex: 1; padding: 24px 32px; box-sizing: border-box; min-width: 0; }
</style>
