<script setup lang="ts">
import { RouterView } from "vue-router";
import { ThemeToggle } from "@slidev-polls/shared/ui";
</script>

<template>
  <div class="voter-shell">
    <header class="voter-header">
      <span class="voter-brand">slidev polls</span>
      <ThemeToggle />
    </header>
    <main class="voter-main">
      <RouterView />
    </main>
  </div>
</template>

<style>
:root {
  color-scheme: light;
}
[data-theme="dark"] :root,
html[data-theme="dark"] {
  color-scheme: dark;
}

body {
  margin: 0;
  background: var(--sp-bg-muted);
  color: var(--sp-fg);
  font-family: var(--sp-font-sans), sans-serif;
}
</style>

<style scoped>
.voter-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--sp-bg-muted);
}
.voter-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  max-width: 36rem;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}
.voter-brand {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--sp-fg);
}
.voter-main {
  flex: 1;
  padding: 0 20px 32px;
  max-width: 36rem;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}
</style>
