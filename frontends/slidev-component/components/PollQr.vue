<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from "vue";
import QRCodeStyling from "qr-code-styling";
import { useVoterUrl } from "../composables/useVoterUrl";
import { buildQrOptions } from "../composables/qrOptions";

const props = defineProps<{
  slug: string;
}>();

// Host comes from the deck (frontmatter/headmatter pollServer) + this slug.
// No network, no auth — a QR is just a printable voter URL.
const voterUrl = useVoterUrl(() => props.slug);

const qrHost = ref<HTMLDivElement | null>(null);
let qr: QRCodeStyling | null = null;

onMounted(async () => {
  await nextTick();
  if (!qrHost.value) return;
  qrHost.value.replaceChildren();
  qr = new QRCodeStyling(buildQrOptions(voterUrl.value));
  qr.append(qrHost.value);
});

// Re-render when the resolved URL changes (e.g. the slug prop changes).
watch(voterUrl, (v) => {
  if (qr) qr.update(buildQrOptions(v));
});
</script>

<template>
  <div class="sp-pollqr" data-testid="poll-qr">
    <div ref="qrHost" class="sp-pollqr__svg" />
    <p class="sp-pollqr__url">{{ voterUrl }}</p>
  </div>
</template>

<style scoped>
.sp-pollqr {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.sp-pollqr__svg {
  width: 100%;
  aspect-ratio: 1 / 1;
}
.sp-pollqr__svg :deep(svg) {
  width: 100%;
  height: 100%;
  display: block;
}
.sp-pollqr__url {
  margin: 0;
  font-family: var(--sp-font-mono, ui-monospace, monospace);
  font-size: 16px;
  color: var(--sp-fg, #111);
  word-break: break-all;
  text-align: center;
}
</style>
