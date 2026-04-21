<script setup lang="ts">
import DeckAuthControl from "./components/DeckAuthControl.vue";
</script>

<!--
  Slidev's global-top.vue slot is auto-picked from an addon's root (research.md D-001) and
  renders above every slide in both single-page and presenter-mode builds. Hosting the auth
  control here satisfies FR-001 / FR-002 — the control is reachable from every slide without
  the presenter having to drop it into slide markdown.
-->
<template>
  <DeckAuthControl />
</template>
