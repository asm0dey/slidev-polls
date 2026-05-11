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

# Slidev Polls — E2E Deck

Consumed only by `frontends/slidev-component/e2e/slidev-results-cross-origin.spec.ts`.

The spec writes `frontends/slidev-demo/data.ts` in `beforeAll` with the live
slug + poll/question UUIDs, then `page.goto`s slide 3 — Vite picks up the
fresh `data.ts` on the full page reload, so PollResults receives the seeded
poll's identifiers.

---

## E2E wiring

Sign-in via the in-deck nav-bar control, then activation POST is fired by the
spec's TS-C02 step (`page.evaluate(fetch)`) so the activation does not depend
on a Vue lifecycle hook racing the auth status flip.

---
layout: center
---

<script setup lang="ts">
import { pollSlug, pollId, q1Id } from "./data";
</script>

## Q1 — Which JVM for the workshop?

<PollResults :slug="pollSlug" :poll-id="pollId" :question-id="q1Id" />

---
layout: center
---

<script setup lang="ts">
import { pollSlug, pollId, q2Id } from "./data";
</script>

## Q2 — Favourite build tool?

<PollResults :slug="pollSlug" :poll-id="pollId" :question-id="q2Id" />
