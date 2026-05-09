<script setup lang="ts">
import { computed, ref } from "vue";
import Chip from "./Chip.vue";
import IconAlert from "./IconAlert.vue";
import { validateOrigin } from "./origin-validator";

const props = defineProps<{ modelValue: string[] }>();
const emit = defineEmits<{ "update:modelValue": [v: string[]] }>();

const draft = ref("");
const error = ref<string | null>(null);

const items = computed(() =>
  props.modelValue.map(o => {
    const r = validateOrigin(o);
    return { raw: o, invalid: !r.ok };
  })
);

function commit() {
  const v = draft.value;
  if (!v.trim()) {
    error.value = null;
    return;
  }
  const r = validateOrigin(v);
  if (!r.ok) {
    error.value = r.message;
    return;
  }
  if (props.modelValue.includes(r.normalized)) {
    error.value = `Already added`;
    return;
  }
  emit("update:modelValue", [...props.modelValue, r.normalized]);
  draft.value = "";
  error.value = null;
}

function remove(idx: number) {
  const next = props.modelValue.slice();
  next.splice(idx, 1);
  emit("update:modelValue", next);
}
</script>

<template>
  <div class="sp-aof">
    <div class="sp-aof-box">
      <Chip
        v-for="(item, i) in items"
        :key="i"
        :label="item.raw"
        :invalid="item.invalid"
        :mono="true"
        data-testid="origin-chip"
        @remove="remove(i)"
      >
        <template v-if="item.invalid" #leading>
          <IconAlert />
        </template>
      </Chip>
      <input
        v-model="draft"
        class="sp-aof-input"
        placeholder="https://… (Enter to add)"
        @keydown.enter.prevent="commit"
      >
    </div>
    <div v-if="error" class="sp-aof-error" data-testid="aof-error" role="alert">
      <IconAlert />
      <span>{{ error }}</span>
    </div>
  </div>
</template>

<style scoped>
.sp-aof { display: flex; flex-direction: column; gap: 8px; }
.sp-aof-box {
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius);
  padding: 8px; display: flex; flex-wrap: wrap; gap: 6px;
  min-height: 42px; background: var(--sp-bg);
}
.sp-aof-input {
  flex: 1; min-width: 160px;
  border: 0; outline: 0; background: transparent;
  font-size: 13px; font-family: var(--sp-font-mono);
  padding: 4px 6px; color: var(--sp-fg);
}
.sp-aof-error {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 10px; background: var(--sp-danger-bg);
  border: 1px solid var(--sp-danger); border-radius: var(--sp-radius-sm);
  font-size: 12px; color: var(--sp-danger-fg);
}
</style>
