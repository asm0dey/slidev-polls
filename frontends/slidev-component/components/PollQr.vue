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
/* Mirrors PollQrButton's overlay card: white rounded card, QR, URL underneath
   — so the inline QR reads the same as the on-demand overlay. */
.sp-pollqr {
  /* Self-bounding: fill the container but never grow past a sane size, so the
     QR fits the slide even when dropped in bare (no wrapper). Capped by both an
     absolute px and a share of the smaller viewport dimension; centered. */
  width: 100%;
  max-width: min(360px, 60vmin);
  margin-inline: auto;
  box-sizing: border-box;
  background: #fff;
  padding: 32px 32px 24px;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
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
  color: #111;
  word-break: break-all;
  text-align: center;
}
</style>
