<script setup lang="ts">
import { ref } from "vue";
import QrPreview from "./QrPreview.vue";

const props = withDefaults(
  defineProps<{
    pollId: string;
    slug: string;
    size?: number;
  }>(),
  { size: 160 }
);

const open = ref(false);

function show() {
  open.value = true;
}
function hide() {
  open.value = false;
}
function toggle() {
  open.value = !open.value;
}
</script>

<template>
  <span class="qrp" @mouseenter="show" @mouseleave="hide" @focusin="show" @focusout="hide">
    <button
      type="button"
      class="qrp__trigger"
      data-testid="qr-popover-trigger"
      :aria-expanded="open"
      @mouseenter="show"
      @mouseleave="hide"
      @focus="show"
      @blur="hide"
      @click.stop="toggle"
    >
      QR
    </button>
    <span v-if="open" class="qrp__panel" role="tooltip">
      <QrPreview data-testid="poll-qr-img" :poll-id="pollId" :slug="slug" :size="size" />
    </span>
  </span>
</template>

<style scoped>
.qrp {
  position: relative;
  display: inline-block;
}
.qrp__trigger {
  background: transparent;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius-sm);
  padding: 4px 8px;
  font-size: 11px;
  color: var(--sp-fg-muted);
  cursor: pointer;
}
.qrp__trigger:hover {
  background: var(--sp-bg-muted);
}
.qrp__panel {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  padding: 10px;
  background: var(--sp-bg);
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
  z-index: 20;
}
</style>
