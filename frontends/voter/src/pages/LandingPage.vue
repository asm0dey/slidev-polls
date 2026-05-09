<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { Button } from "@slidev-polls/shared/ui";

const router = useRouter();
const slug = ref("");
const error = ref<string | null>(null);

const slugPattern = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const isSlugShaped = computed(
  () => slug.value.length >= 3 && slug.value.length <= 40 && slugPattern.test(slug.value)
);

function onSubmit() {
  const candidate = slug.value.trim();
  if (!candidate) {
    error.value = "Enter the slug the presenter shared.";
    return;
  }
  if (!isSlugShaped.value) {
    error.value =
      "That doesn't look like a poll link. Slugs are lowercase letters, numbers, and dashes (3–40 characters).";
    return;
  }
  error.value = null;
  router.push({ name: "poll", params: { slug: candidate } });
}
</script>

<template>
  <section class="landing" data-testid="landing-page">
    <div class="landing__hero">
      <h1 class="landing__title">Join a session</h1>
      <p class="landing__sub">Enter the link the presenter shared, or scan the QR code on the slide.</p>
    </div>
    <form class="landing__form" data-testid="landing-form" @submit.prevent="onSubmit">
      <input
        v-model="slug"
        class="landing__input"
        data-testid="landing-slug"
        type="text"
        autocomplete="off"
        spellcheck="false"
        autocapitalize="off"
        inputmode="url"
        placeholder="my-talk"
      >
      <Button type="submit" data-testid="landing-submit">Join</Button>
      <p v-if="error" role="alert" data-testid="landing-error" class="landing__error">
        {{ error }}
      </p>
    </form>
    <p class="landing__hint">No account, no install · open while you vote</p>
  </section>
</template>

<style scoped>
.landing {
  display: flex; flex-direction: column; gap: 24px;
  background: var(--sp-bg);
  border-radius: var(--sp-radius-xl);
  padding: 32px 24px;
  border: 1px solid var(--sp-border);
}
.landing__hero { display: flex; flex-direction: column; gap: 8px; }
.landing__title {
  font-size: 24px; font-weight: 600;
  letter-spacing: -0.02em; line-height: 1.2; margin: 0;
  color: var(--sp-fg);
}
.landing__sub { font-size: 14px; color: var(--sp-fg-subtle); margin: 0; line-height: 1.5; }
.landing__form { display: flex; flex-direction: column; gap: 10px; }
.landing__input {
  width: 100%; box-sizing: border-box;
  padding: 16px;
  border: 1px solid var(--sp-border); border-radius: var(--sp-radius-lg);
  font-size: 22px; font-weight: 600; letter-spacing: 0.2em;
  text-align: center; text-transform: uppercase;
  background: var(--sp-bg); color: var(--sp-fg);
  font-variant-numeric: tabular-nums; font-family: var(--sp-font-sans);
}
.landing__input:focus { outline: 2px solid var(--sp-accent-ring); border-color: var(--sp-accent); }
.landing__error {
  background: var(--sp-danger-bg); color: var(--sp-danger-fg);
  border: 1px solid var(--sp-danger); border-radius: var(--sp-radius-sm);
  padding: 8px 10px; font-size: 12px; margin: 0;
}
.landing__hint {
  text-align: center; font-size: 11px;
  letter-spacing: 0.04em;
  color: var(--sp-fg-faint); margin: 0;
}
</style>
