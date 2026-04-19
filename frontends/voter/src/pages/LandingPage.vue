<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";

// Minimal join-by-slug entry point for a visitor who landed on `/` directly (no QR, no pre-
// baked link). The slug input is validated client-side against the same kebab-case regex the
// server's SlugValidator enforces so we do not route to a slug the server will only reject.

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
    <h1>Slidev Polls</h1>
    <p>
      Enter the short link the presenter shared, or scan the QR code on the slide.
    </p>
    <form class="landing__form" data-testid="landing-form" @submit.prevent="onSubmit">
      <label class="landing__field">
        <span>Poll link</span>
        <input
          v-model="slug"
          data-testid="landing-slug"
          type="text"
          autocomplete="off"
          spellcheck="false"
          autocapitalize="off"
          inputmode="url"
          placeholder="e.g. my-talk"
        />
      </label>
      <button type="submit" data-testid="landing-submit">Join</button>
      <p v-if="error" role="alert" data-testid="landing-error" class="landing__error">
        {{ error }}
      </p>
    </form>
  </section>
</template>

<style scoped>
.landing {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.landing__form {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.landing__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.landing__field input {
  padding: 0.5rem 0.75rem;
  font-size: 1rem;
  border: 1px solid #ccc;
  border-radius: 6px;
}
.landing__error {
  background: #fdecea;
  color: #b71c1c;
  padding: 0.5rem 0.75rem;
  border-radius: 4px;
  margin: 0;
}
</style>
