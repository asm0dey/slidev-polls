<script setup lang="ts">
import { ref, watch } from "vue";
import Button from "./Button.vue";

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    body?: string;
    confirmLabel?: string;
    cancelLabel?: string;
    tone?: "neutral" | "danger";
  }>(),
  { body: "", confirmLabel: "Confirm", cancelLabel: "Cancel", tone: "neutral" }
);

const emit = defineEmits<{
  (e: "confirm"): void;
  (e: "cancel"): void;
}>();

const dialogRef = ref<HTMLDialogElement | null>(null);

watch(
  () => props.open,
  (isOpen) => {
    const d = dialogRef.value;
    if (!d) return;
    if (isOpen && !d.open) {
      if (typeof d.showModal === "function") d.showModal();
      else d.setAttribute("open", "");
    }
    if (!isOpen && d.open) d.close();
  },
  { flush: "post", immediate: true }
);

function onCancel() {
  emit("cancel");
}
function onConfirm() {
  emit("confirm");
}
</script>

<template>
  <dialog
    ref="dialogRef"
    class="cd"
    data-testid="confirm-dialog"
    @cancel.prevent="onCancel"
    @close="onCancel"
  >
    <h2 class="cd__title">{{ title }}</h2>
    <p v-if="body" class="cd__body">{{ body }}</p>
    <div class="cd__actions">
      <Button variant="ghost" data-testid="confirm-dialog-cancel" @click="onCancel">
        {{ cancelLabel }}
      </Button>
      <Button
        :variant="tone === 'danger' ? 'danger' : 'primary'"
        data-testid="confirm-dialog-confirm"
        @click="onConfirm"
      >
        {{ confirmLabel }}
      </Button>
    </div>
  </dialog>
</template>

<style scoped>
.cd {
  border: none;
  border-radius: var(--sp-radius-lg, 8px);
  padding: 20px 22px;
  max-width: 420px;
  background: var(--sp-bg);
  color: var(--sp-fg);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
}
.cd::backdrop {
  background: rgba(0, 0, 0, 0.4);
}
.cd__title {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 8px;
}
.cd__body {
  font-size: 13px;
  color: var(--sp-fg-subtle);
  margin: 0 0 18px;
  line-height: 1.5;
}
.cd__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
