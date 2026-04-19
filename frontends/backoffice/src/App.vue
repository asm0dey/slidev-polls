<script setup lang="ts">
import { RouterLink, RouterView } from "vue-router";
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <h1><RouterLink to="/polls">Slidev Polls</RouterLink></h1>
      <nav>
        <RouterLink to="/polls">Polls</RouterLink>
        <RouterLink to="/polls/new">New poll</RouterLink>
      </nav>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  font-family: system-ui, sans-serif;
  max-width: 60rem;
  margin: 0 auto;
  padding: 1rem;
}
.app-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  border-bottom: 1px solid #ddd;
  padding-bottom: 0.5rem;
  margin-bottom: 1rem;
}
.app-header h1 {
  font-size: 1.25rem;
  margin: 0;
}
.app-header h1 a {
  color: inherit;
  text-decoration: none;
}
.app-header nav a {
  margin-left: 1rem;
}
</style>
