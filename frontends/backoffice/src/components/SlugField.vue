<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { checkSlug } from "../lib/slug-rules";

const props = withDefaults(
  defineProps<{
    modelValue: string;
    label?: string;
    placeholder?: string;
  }>(),
  { label: "Slug", placeholder: "my-talk" }
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

function onInput(event: Event) {
  const target = event.target as HTMLInputElement;
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
  font-family: ui-monospace, monospace;
  padding: 0.4rem 0.5rem;
  border: 1px solid #ccc;
  border-radius: 4px;
}
.slug-field__input[aria-invalid="true"] {
  border-color: #c0392b;
}
.slug-field__error {
  color: #c0392b;
  font-size: 0.875rem;
  margin: 0;
}
</style>
