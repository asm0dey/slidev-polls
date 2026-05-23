<script setup lang="ts">
defineProps<{
  modelValue?: string;
  placeholder?: string;
  type?: string;
  invalid?: boolean;
  autocomplete?: string;
  name?: string;
  id?: string;
}>();
defineEmits<{ "update:modelValue": [v: string] }>();
</script>

<template>
  <input
    class="sp-input"
    :data-invalid="invalid || undefined"
    :type="type ?? 'text'"
    :value="modelValue"
    :placeholder="placeholder"
    :autocomplete="autocomplete"
    :name="name"
    :id="id"
    @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
  />
</template>

<style scoped>
.sp-input {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  font-family: var(--sp-font-sans), sans-serif;
  font-size: 13px;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  background: var(--sp-bg);
  color: var(--sp-fg);
  color-scheme: inherit;
}
.sp-input::placeholder {
  color: var(--sp-fg-faint);
}
.sp-input:focus {
  outline: 2px solid var(--sp-accent-ring);
  outline-offset: 0;
  border-color: var(--sp-accent);
}
.sp-input[data-invalid] {
  border-color: var(--sp-danger);
}
</style>
