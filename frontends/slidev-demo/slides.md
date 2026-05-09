---
theme: default
title: Slidev Polls — Auth-Gated Demo
addons:
  - "@polls/slidev-addon"
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
3. On each question hit **Copy snippet** in the editor — paste the resulting
   `<PollResults />` tags into Q1 / Q2 slides below (replacing the stubs).
4. Mint a deck token on the **Deck tokens** page; copy the plaintext token.
5. Click **sign in** in the Slidev nav bar and paste the token. The button
   flips to *signed in: &lt;label&gt;*.

---

## How this deck is wired

- The addon mounts a **sign-in button in the nav bar** (hover the toolbar to
  reveal it during play). Pre-sign-in the button reads *sign in*; click to
  open the token popover.
- Paste the deck token; the toolbar flips to **signed in: &lt;label&gt;**.
- Poll slides embed `<PollResults />` — on mount they POST
  `/api/deck/polls/{pollId}/activate` **only** if the composable is
  `signed-in`. Anonymous viewers never hijack the active question.
- Revoke the token from the backoffice → next navigation flips the control
  back to *not signed in* with *credential not recognised*.

---
layout: center
---

## Q1 — paste your snippet here

<!--
Replace the stub below with the snippet copied from the backoffice
(question editor → Copy snippet). The snippet auto-includes
slug, pollId, questionId. The deck token is NOT in the markup —
the in-deck auth control supplies it at runtime.

Example shape:

  <PollResults
    slug="my-talk"
    pollId="11111111-1111-1111-1111-111111111111"
    questionId="22222222-2222-2222-2222-222222222222"
  />
-->

<PollResults slug="my-talk" />

<!--
Navigate here while **signed in** to activate this question on the backend.
Open a second tab without signing in — same tallies, never fires activate.
-->

---
layout: center
---

## Q2 — paste your second snippet here

<PollResults slug="my-talk" />

---

## Vote against either question

| URL                                      | What it is           |
|------------------------------------------|----------------------|
| `http://localhost:8080/<slug>`           | voter SPA slug route |
| network panel → `/api/polls/…/stream`    | live SSE feed        |
| network panel → `/api/deck/.../activate` | presenter-only POST  |

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

Mint more tokens any time from the backoffice **Deck tokens** page.
