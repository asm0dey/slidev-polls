<template>
  <span class="sp-livedot" aria-hidden="true" />
</template>

<style scoped>
.sp-livedot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--sp-success);
  box-shadow: 0 0 0 0 var(--sp-success);
  animation: sp-pulse 2s var(--sp-ease) infinite;
}
@keyframes sp-pulse {
  0% {
    box-shadow: 0 0 0 0 color-mix(in srgb, var(--sp-success) 60%, transparent);
  }
  70% {
    box-shadow: 0 0 0 6px transparent;
  }
  100% {
    box-shadow: 0 0 0 0 transparent;
  }
}
@media (prefers-reduced-motion: reduce) {
  .sp-livedot {
    animation: none;
  }
}
</style>
