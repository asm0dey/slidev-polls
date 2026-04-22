<script setup lang="ts">
import CustomNavControls from "./components/CustomNavControls.vue";
</script>

<!--
  Slidev auto-picks custom-nav-controls.vue from an addon's root and mounts it inside the
  built-in nav bar in both SPA and presenter modes (sli.dev/features/global-layers). Hosting
  the deck auth UI here keeps the slide canvas clean (BUG-001) while still satisfying FR-001
  once the nav bar is revealed. Auto-hide during play is accepted per clarification
  2026-04-22 — hover/focus reveals the control.
-->
<template>
  <CustomNavControls />
</template>
