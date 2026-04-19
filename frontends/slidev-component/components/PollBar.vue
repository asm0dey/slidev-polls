<template>
  <li class="poll-bar" :data-testid="`poll-bar-${optionId}`">
    <div class="poll-bar__row">
      <span class="poll-bar__label">{{ label }}</span>
      <span class="poll-bar__count">{{ count }}</span>
    </div>
    <div
      class="poll-bar__track"
      role="progressbar"
      :aria-valuenow="percentage"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-label="`${label}: ${count} of ${total} votes`"
    >
      <div class="poll-bar__fill" :style="{ width: `${percentage}%` }" />
    </div>
  </li>
</template>

<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  optionId: string;
  label: string;
  count: number;
  total: number;
}>();

const percentage = computed(() => {
  if (props.total <= 0) return 0;
  return Math.round((props.count / props.total) * 100);
});
</script>

<style scoped>
.poll-bar {
  list-style: none;
  margin: 0 0 0.75rem 0;
  padding: 0;
}
.poll-bar__row {
  display: flex;
  justify-content: space-between;
  font-size: 1em;
  margin-bottom: 0.2rem;
}
.poll-bar__count {
  font-variant-numeric: tabular-nums;
  color: #444;
}
.poll-bar__track {
  background: #eef0f4;
  border-radius: 3px;
  height: 10px;
  overflow: hidden;
}
.poll-bar__fill {
  background: linear-gradient(90deg, #4f8ef7, #2563eb);
  height: 100%;
  transition: width 200ms ease-out;
}
</style>
