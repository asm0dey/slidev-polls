<script setup lang="ts">
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    pollId: string;
    slug?: string;
    size?: number;
  }>(),
  { slug: undefined, size: 160 }
);

const src = computed(() => `/api/admin/polls/${encodeURIComponent(props.pollId)}/qr.png`);
const alt = computed(() => (props.slug ? `Join QR for ${props.slug}` : "Poll join QR"));
</script>

<template>
  <img class="qr-preview" :src="src" :alt="alt" :width="size" :height="size" loading="lazy" />
</template>

<style scoped>
.qr-preview {
  display: block;
  border: 1px solid #ddd;
  border-radius: 4px;
  background: white;
}
</style>
