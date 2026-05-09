---
theme: default
title: Slidev Polls — Auth-Gated Demo
addons:
  - "@polls/slidev-addon"
colorSchema: light
drawings:
  persist: false
transition: slide-left
---

# Slidev Polls

In-deck auth control + deck-token activation — feature 002 demo.

Run `bun run seed` first, then click **sign in** in the Slidev nav bar
and paste the printed deck token into the popover before navigating
past this slide.

---

## How this deck is wired

- The Slidev addon mounts a **sign-in button in the nav bar** (hover
  the toolbar to reveal it during play). Pre-sign-in the button reads
  *sign in*; click to open the token popover.
- Paste the deck token into the popover; the toolbar button flips to
  **signed in: demo-deck**.
- Poll slides embed `<PollResults />` — on mount they POST
  `/api/deck/polls/{pollId}/activate` **only** if the composable is
  `signed-in`. Anonymous viewers never hijack the active question.
- Revoke the token from the backoffice → next navigation flips the
  control back to *not signed in* with *credential not recognised*.

---
layout: center
---

<script setup lang="ts">
import { pollServer, pollSlug, pollId, q1Id } from "./data";
</script>

## Q1 — Which JVM for the workshop?

<PollResults
  :slug="pollSlug"
  :server="pollServer"
  :poll-id="pollId"
  :question-id="q1Id"
/>

<!--
Navigate here while **signed in** to activate Q1 on the backend.
Open a second tab without signing in — you see the same tallies but
never fire an activation POST.
-->

---
layout: center
---

<script setup lang="ts">
import { pollServer, pollSlug, pollId, q2Id } from "./data";
</script>

## Q2 — Favourite build tool?

<PollResults
  :slug="pollSlug"
  :server="pollServer"
  :poll-id="pollId"
  :question-id="q2Id"
/>

---

## Vote against either question

| URL                                      | What it is           |
|------------------------------------------|----------------------|
| `http://localhost:8080/{{ slug }}`       | voter SPA slug route |
| network panel → `/api/polls/…/stream`    | live SSE feed        |
| network panel → `/api/deck/.../activate` | presenter-only POST  |

<script setup lang="ts">
import { pollSlug as slug } from "./data";
</script>

---

## What to look for

| Observable                              | Where                          |
|-----------------------------------------|--------------------------------|
| auth control flips to *signed in: label*| top-right overlay              |
| activation POST with `X-Deck-Token`     | browser devtools network panel |
| anonymous viewer fires zero POSTs       | second browser profile         |
| revocation → *credential not recognised*| first browser after DELETE     |
| *live updates paused* on SSE drop       | kill the SSE from devtools     |

---
layout: end
---

# Done.

Keep the `curl` from `scripts/seed.sh` around to mint more tokens.
