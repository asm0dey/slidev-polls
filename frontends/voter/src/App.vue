<script setup lang="ts">
import { RouterView } from "vue-router";
</script>

<template>
  <div class="voter-shell">
    <main class="voter-main">
      <RouterView />
    </main>
  </div>
</template>

<style>
:root {
  color-scheme: light;
  font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
}

body {
  margin: 0;
  background: #f8f8fa;
  color: #222;
}

.voter-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.voter-main {
  flex: 1;
  padding: 1.5rem 1rem;
  max-width: 36rem;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}
</style>
