<template>
  <div data-testid="slug-field-stub">
    <input data-testid="slug-input" :value="modelValue" />
  </div>
</template>

<script setup lang="ts">
defineProps<{ modelValue: string }>();
defineEmits<{
  (e: "update:modelValue", v: string): void;
  (e: "update:valid", v: boolean): void;
}>();
</script>
