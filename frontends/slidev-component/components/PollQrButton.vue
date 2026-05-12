<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from "vue";
import QRCodeStyling from "qr-code-styling";

const props = defineProps<{
  voterUrl: string;
}>();

const open = ref(false);
const qrHost = ref<HTMLDivElement | null>(null);
let qr: QRCodeStyling | null = null;

function buildOptions(url: string) {
  return {
    width: 512,
    height: 512,
    type: "svg" as const,
    data: url,
    margin: 8,
    qrOptions: { errorCorrectionLevel: "M" as const },
    dotsOptions: { type: "rounded" as const, color: "#111111" },
    cornersSquareOptions: { type: "extra-rounded" as const, color: "#111111" },
    cornersDotOptions: { type: "dot" as const, color: "#111111" },
    backgroundOptions: { color: "#ffffff" }
  };
}

async function openOverlay(): Promise<void> {
  open.value = true;
  await nextTick();
  if (!qrHost.value) return;
  qrHost.value.replaceChildren();
  qr = new QRCodeStyling(buildOptions(props.voterUrl));
  qr.append(qrHost.value);
}

function close(): void {
  open.value = false;
  qr = null;
}

function toggle(): void {
  if (open.value) {
    close();
    return;
  }
  void openOverlay();
}

watch(
  () => props.voterUrl,
  (v) => {
    if (open.value && qr) qr.update(buildOptions(v));
  }
);

function onKey(e: KeyboardEvent): void {
  if (e.key === "Escape") close();
}

if (typeof document !== "undefined") {
  document.addEventListener("keydown", onKey);
  onBeforeUnmount(() => document.removeEventListener("keydown", onKey));
}
</script>

<template>
  <button
    type="button"
    class="sp-qr-toggle"
    data-testid="poll-qr-toggle"
    aria-label="Show QR code for this poll"
    @click="toggle"
  >
    <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
      <path
        fill="currentColor"
        d="M2 2h5v5H2V2zm1 1v3h3V3H3zm6-1h5v5H9V2zm1 1v3h3V3h-3zM2 9h5v5H2V9zm1 1v3h3v-3H3zm6 0h2v2H9v-2zm3 0h2v2h-2v-2zm-3 3h2v2H9v-2zm3 0h2v2h-2v-2z"
      />
    </svg>
  </button>
  <Teleport to="body">
    <div
      v-if="open"
      class="sp-qr-overlay"
      data-testid="poll-qr-overlay"
      role="dialog"
      aria-modal="true"
      @click.self="close"
    >
      <div class="sp-qr-card" @click.stop>
        <button type="button" class="sp-qr-close" aria-label="Close QR overlay" @click="close">
          ×
        </button>
        <div ref="qrHost" class="sp-qr-svg" />
        <p class="sp-qr-url">{{ voterUrl }}</p>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.sp-qr-toggle {
  position: absolute;
  top: 6px;
  right: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: transparent;
  color: var(--sp-fg-muted);
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius-sm);
  cursor: pointer;
  z-index: 2;
}
.sp-qr-toggle:hover {
  background: var(--sp-bg-muted);
  color: var(--sp-fg);
}
.sp-qr-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.72);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}
.sp-qr-card {
  position: relative;
  background: #fff;
  padding: 32px 32px 24px;
  border-radius: 12px;
  max-width: min(80vmin, 720px);
  width: 80vmin;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.sp-qr-close {
  position: absolute;
  top: 8px;
  right: 12px;
  width: 32px;
  height: 32px;
  font-size: 22px;
  line-height: 1;
  background: transparent;
  border: none;
  cursor: pointer;
  color: #333;
}
.sp-qr-svg {
  width: 100%;
  aspect-ratio: 1 / 1;
}
.sp-qr-svg :deep(svg) {
  width: 100%;
  height: 100%;
  display: block;
}
.sp-qr-url {
  margin: 0;
  font-family: var(--sp-font-mono, ui-monospace, monospace);
  font-size: 16px;
  color: #111;
  word-break: break-all;
  text-align: center;
}
</style>
