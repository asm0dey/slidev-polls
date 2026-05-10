---
theme: default
title: Slidev Polls — E2E Deck
addons:
  - "@slidev-polls/component"
colorSchema: dark
# pollServer drives the in-deck auth control + SSE + activate POSTs to the
# backend origin. Slidev serves this deck on :3030; the backend lives on :8080.
pollServer: http://localhost:8080
---

# Slidev Polls E2E

This deck is consumed only by the cross-origin Playwright spec
(`frontends/slidev-component/e2e/slidev-results-cross-origin.spec.ts`).
It expects `?slug=…&pollId=…&q1Id=…&q2Id=…` on every navigation; the
spec drives that via `page.goto`.

---

## E2E wiring

Slide 3 below is the cross-origin spec's first PollResults slide. The
deck-auth control in the nav bar signs in against `pollServer`; the
spec's TS-C02 fires the activation POST directly via
`page.evaluate(fetch)` after sign-in succeeds.

---
layout: center
---

<script setup lang="ts">
// useRoute() does not work cleanly here because @slidev/client uses a
// bundled vue-router instance whose injection key differs from a sibling
// vue-router install — fall back to window.location.search at mount.
const params = new URLSearchParams(window.location.search);
const slug = params.get("slug") ?? "demo";
const pollId = params.get("pollId") ?? "00000000-0000-0000-0000-000000000000";
const questionId = params.get("q1Id") ?? "00000000-0000-0000-0000-000000000001";
</script>

## Q1 — Which JVM for the workshop?

<PollResults :slug="slug" :poll-id="pollId" :question-id="questionId" />

---
layout: center
---

<script setup lang="ts">
const params = new URLSearchParams(window.location.search);
const slug = params.get("slug") ?? "demo";
const pollId = params.get("pollId") ?? "00000000-0000-0000-0000-000000000000";
const questionId = params.get("q2Id") ?? "00000000-0000-0000-0000-000000000002";
</script>

## Q2 — Favourite build tool?

<PollResults :slug="slug" :poll-id="pollId" :question-id="questionId" />
