---
theme: default
title: Slidev Polls — Auth-Gated Demo
addons:
  - "@slidev-polls/component"
colorSchema: dark
drawings:
  persist: false
transition: slide-left
# Custom — read by PollPanel via useSlideContext().$slidev.configs and
# routed at the in-deck auth control + SSE + activate calls. Drop this
# if the deck runs same-origin with the backend, or override per slide
# by adding `pollServer: ...` to that slide's own frontmatter block.
pollServer: http://localhost:8080
---

# Slidev Polls

In-deck auth control + deck-token activation.

To run this deck:

1. Boot the backend (`task up:detached` or `task slidev:demo`).
2. Open `http://localhost:8080/admin/`, do first-run setup, create a poll
   with two questions and add `http://localhost:3030` to **Allowed origins**.
3. Copy the poll slug, poll ID, and both question IDs from the backoffice.
4. Navigate to the deck with query params:
   `http://localhost:3030/3?slug=…&pollId=…&q1Id=…&q2Id=…`
   The deck reads them from `window.location.search` and passes them to the slides.
5. Mint a deck token on the **Deck tokens** page; copy the plaintext token.
6. Click **sign in** in the Slidev nav bar and paste the token. The button
   flips to _signed in: &lt;label&gt;_.

---

## How this deck is wired

- The addon mounts a **sign-in button in the nav bar** (hover the toolbar to
  reveal it during play). Pre-sign-in the button reads _sign in_; click to
  open the token popover.
- Paste the deck token; the toolbar flips to **signed in: &lt;label&gt;**.
- Poll slides embed `<PollResults />` — on mount they POST
  `/api/deck/polls/{pollId}/activate` **only** if the composable is
  `signed-in`. Anonymous viewers never hijack the active question.
- Revoke the token from the backoffice → next navigation flips the control
  back to _not signed in_ with _credential not recognised_.

---
layout: center
---

<script setup lang="ts">
// Operator workflow: navigate the deck with `?slug=…&pollId=…&q1Id=…&q2Id=…`
// (one URL covers the whole deck). Falls back to the demo-stub IDs so the
// deck renders something sensible when run without query params.
// Note: query params are read from window.location directly to avoid a
// vue-router dual-instance problem in the Slidev Vite dev server.
const q = new URLSearchParams(
  typeof window !== "undefined" ? window.location.search : ""
);
const slug = q.get("slug") || "demo";
const pollId = q.get("pollId") || "00000000-0000-0000-0000-000000000000";
const questionId = q.get("q1Id") || "00000000-0000-0000-0000-000000000001";
</script>

## Q1 — Which JVM for the workshop?

<PollResults :slug="slug" :poll-id="pollId" :question-id="questionId" />

<!--
Navigate here while **signed in** to activate this question on the backend.
Open a second tab without signing in — same tallies, never fires activate.
-->

---
layout: center
---

<script setup lang="ts">
// Operator workflow: navigate the deck with `?slug=…&pollId=…&q1Id=…&q2Id=…`
// (one URL covers the whole deck). Falls back to the demo-stub IDs so the
// deck renders something sensible when run without query params.
// Note: query params are read from window.location directly to avoid a
// vue-router dual-instance problem in the Slidev Vite dev server.
const q = new URLSearchParams(
  typeof window !== "undefined" ? window.location.search : ""
);
const slug = q.get("slug") || "demo";
const pollId = q.get("pollId") || "00000000-0000-0000-0000-000000000000";
const questionId = q.get("q2Id") || "00000000-0000-0000-0000-000000000002";
</script>

## Q2 — Favourite build tool?

<PollResults :slug="slug" :poll-id="pollId" :question-id="questionId" />

---

## Vote against either question

| URL                                      | What it is           |
| ---------------------------------------- | -------------------- |
| `http://localhost:8080/<slug>`           | voter SPA slug route |
| network panel → `/api/polls/…/stream`    | live SSE feed        |
| network panel → `/api/deck/.../activate` | presenter-only POST  |

---

## What to look for

| Observable                               | Where                          |
| ---------------------------------------- | ------------------------------ |
| auth control flips to _signed in: label_ | top-right overlay              |
| activation POST with `X-Deck-Token`      | browser devtools network panel |
| anonymous viewer fires zero POSTs        | second browser profile         |
| revocation → _credential not recognised_ | first browser after DELETE     |
| _live updates paused_ on SSE drop        | kill the SSE from devtools     |

---
layout: end
---

# Done.

Mint more tokens any time from the backoffice **Deck tokens** page.
