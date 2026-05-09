<script setup lang="ts">
import PollPanel from "./PollPanel.vue";

defineProps<{
  slug: string;
  server?: string;
  questionId?: string;
  pollId?: string;
}>();
</script>

<template>
  <PollPanel v-bind="$props" />
</template>
