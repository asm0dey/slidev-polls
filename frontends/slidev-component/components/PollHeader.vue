<template>
  <header class="poll-header">
    <p v-if="ordinal !== undefined" class="poll-header__ordinal">Question {{ ordinal }}</p>
    <h2 class="poll-header__prompt">{{ prompt }}</h2>
    <p v-if="total > 0" class="poll-header__total">{{ total }} {{ total === 1 ? "vote" : "votes" }}</p>
  </header>
</template>

<script setup lang="ts">
defineProps<{
  prompt: string;
  ordinal?: number;
  total: number;
}>();
</script>

<style scoped>
.poll-header {
  margin-bottom: 1rem;
}
.poll-header__ordinal {
  color: #888;
  font-size: 0.8em;
  margin: 0 0 0.25rem 0;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}
.poll-header__prompt {
  margin: 0;
  font-size: 1.4em;
  line-height: 1.25;
}
.poll-header__total {
  color: #666;
  font-size: 0.85em;
  margin: 0.35rem 0 0 0;
}
</style>
