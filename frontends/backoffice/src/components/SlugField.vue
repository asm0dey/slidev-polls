<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { checkSlug } from "../lib/slug-rules";

const props = withDefaults(
  defineProps<{
    modelValue: string;
    label?: string;
    placeholder?: string;
    autoSeed?: string;
    mode?: "create" | "edit";
  }>(),
  { label: "Slug", placeholder: "my-talk", autoSeed: "", mode: "create" }
);

const emit = defineEmits<{
  (e: "update:modelValue", v: string): void;
  (e: "update:valid", v: boolean): void;
}>();

// Track the input value internally so validation stays accurate even when
// the parent doesn't (yet) round-trip update:modelValue back into the prop —
// e.g., during the first tick of a v-model bridge or in tests that mount
// without a wrapping parent.
const internalValue = ref(props.modelValue);
watch(
  () => props.modelValue,
  (v) => {
    internalValue.value = v;
  }
);

const result = computed(() => checkSlug(internalValue.value));

// Suppress the "Enter a slug." message until the field has been touched —
// surfacing it on initial render would be noise on a brand-new poll form.
const showError = computed(() => result.value.valid === false && result.value.reason !== "EMPTY");

watch(
  result,
  (next) => {
    emit("update:valid", next.valid);
  },
  { immediate: true }
);

const userEdited = ref(false);

function toSlug(s: string): string {
  return s
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[^\p{Letter}\p{Number}]+/gu, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 40);
}

watch(
  () => props.autoSeed,
  (next) => {
    if (props.mode !== "create") return;
    if (userEdited.value) return;
    const seeded = toSlug(next);
    if (seeded !== internalValue.value) {
      internalValue.value = seeded;
      emit("update:modelValue", seeded);
    }
  },
  { immediate: true }
);

function onInput(event: Event) {
  const target = event.target as HTMLInputElement;
  userEdited.value = true;
  internalValue.value = target.value;
  emit("update:modelValue", target.value);
}
</script>

<template>
  <label class="slug-field">
    <span class="slug-field__label">{{ label }}</span>
    <input
      data-testid="slug-input"
      type="text"
      class="slug-field__input"
      autocomplete="off"
      spellcheck="false"
      :value="modelValue"
      :placeholder="placeholder"
      :aria-invalid="showError"
      @input="onInput"
    />
    <p v-if="showError" data-testid="slug-error" class="slug-field__error" role="alert">
      {{ result.message }}
    </p>
  </label>
</template>

<style scoped>
.slug-field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.slug-field__label {
  font-weight: 600;
}
.slug-field__input {
  font-family: var(--sp-font-mono);
  padding: 0.4rem 0.5rem;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  background: var(--sp-bg);
  color: var(--sp-fg);
  color-scheme: inherit;
}
.slug-field__input::placeholder {
  color: var(--sp-fg-faint);
}
.slug-field__input:focus {
  outline: 2px solid var(--sp-accent-ring);
  outline-offset: 0;
  border-color: var(--sp-accent);
}
.slug-field__input[aria-invalid="true"] {
  border-color: var(--sp-danger);
}
.slug-field__error {
  color: var(--sp-danger);
  font-size: 0.875rem;
  margin: 0;
}
</style>
