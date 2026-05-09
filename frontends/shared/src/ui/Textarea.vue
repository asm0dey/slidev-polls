<script setup lang="ts">
defineProps<{
  modelValue?: string;
  placeholder?: string;
  maxlength?: number;
}>();
defineEmits<{ "update:modelValue": [v: string] }>();
</script>

<template>
  <div class="sp-textarea-wrap">
    <textarea
      class="sp-textarea"
      :value="modelValue"
      :placeholder="placeholder"
      :maxlength="maxlength"
      @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
    />
    <div v-if="maxlength" class="sp-textarea-count">
      {{ (modelValue ?? "").length }} / {{ maxlength }}
    </div>
  </div>
</template>

<style scoped>
.sp-textarea-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.sp-textarea {
  width: 100%;
  box-sizing: border-box;
  font-family: var(--sp-font-sans);
  font-size: 14px;
  padding: 12px 14px;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  background: var(--sp-bg);
  color: var(--sp-fg);
  line-height: 1.5;
  resize: none;
  min-height: 120px;
}
.sp-textarea:focus {
  outline: 2px solid var(--sp-accent-ring);
  border-color: var(--sp-accent);
}
.sp-textarea-count {
  font-size: 11px;
  color: var(--sp-fg-faint);
  text-align: right;
  font-variant-numeric: tabular-nums;
}
</style>
