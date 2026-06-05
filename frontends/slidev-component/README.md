# @slidev-polls/component

Slidev addon that renders live audience-poll results on-slide, backed by the
[slidev-polls](https://github.com/asm0dey/slidev-polls) backend.

## Install

```bash
npm install @slidev-polls/component
# or
bun add @slidev-polls/component
```

## Enable in your deck

In `slides.md` frontmatter:

```yaml
---
addons:
  - "@slidev-polls/component"
# Backend URL the addon hits for SSE, activation, and deck-token auth.
# Omit if the deck is served same-origin with the backend.
pollServer: https://polls.example.com
---
```

The addon reads `pollServer` via `useSlideContext().$slidev.configs`. Override
per-slide by adding `pollServer:` to that slide's own frontmatter block.

Alternative (programmatic) — set the backend once from a Slidev `setup/main.ts`
or `data.ts`:

```ts
import { configureDeckAuthBackend } from "@slidev-polls/component";

configureDeckAuthBackend("https://polls.example.com");
```

Frontmatter wins when both are set.

It also mounts a **sign-in button in the Slidev nav bar** for deck-token
auth (paste the token minted in the backoffice → toolbar flips to
_signed in: <label>_).

## Embed poll results on a slide

Don't hand-author the markup. Open the backoffice question editor →
**Copy snippet** → paste into the slide. The snippet pre-fills `slug`,
`pollId`, `questionId`:

```md
<PollResults
  slug="my-talk"
  pollId="78a7aa06-68ea-498e-b1a8-f9faba8bcb2c"
  questionId="4cc12084-d866-44e1-ad86-d012d9511ba8"
/>
```

On mount, when the presenter is signed in, `PollResults` POSTs
`/api/deck/polls/{pollId}/activate` to make this question the live one.
Anonymous viewers see the same tallies but never trigger activation.

### QR code overlay

While signed in as a presenter, every `<PollResults>` rendered on a slide
shows a small QR-toggle button in its top-right corner. Clicking it opens a
fullscreen overlay with a styled rounded-dot QR code (rendered locally via
`qr-code-styling`, nothing leaves the browser) plus the printed voter URL
— `${pollServer || window.location.origin}/${slug}` — so the audience can
scan to join without typing. Click the backdrop, the close button, or press
`Escape` to dismiss. The button is hidden when the deck is not signed in.

### Inline QR code (`<PollQr>`)

`<PollQr slug="my-poll" />` renders an always-visible, inline QR code for a
poll's voter URL on any slide — no button, no overlay, no sign-in. It fills its
container width and prints the URL underneath. The host is taken from
`pollServer` in the slide frontmatter or deck headmatter (falling back to the
deck's own origin), and the `slug` prop selects the poll, so the encoded URL is
`${pollServer || window.location.origin}/${slug}`. The QR is generated locally
with `qr-code-styling`; nothing leaves the browser. Use this when you want a
permanent join code on a slide; use the presenter-only QR-toggle button on
`<PollResults>` when you want an on-demand fullscreen overlay instead.

## Peer dependencies

- `@slidev/client ^52.0.0`
- `vue ^3.5`

(Tested against Slidev `52.15.x`.)

## License

GPL-3.0-or-later. See [LICENSE](./LICENSE).

## Reading poll results from other slides

`PollResults` keeps a deck-wide reactive cache of every poll it has seen, keyed
by an author-chosen `name`. Tag each panel with a short, human-readable name and
read it back from any other slide:

```markdown
<PollResults slug="my-talk" pollId="…" questionId="…" name="q1" />
<PollResults slug="my-talk" pollId="…" questionId="…" name="q2" />
```

```vue
<script setup>
import { computed } from "vue";
import { usePollResults } from "@slidev-polls/component";

const q1 = usePollResults("q1");
const q2 = usePollResults("q2");

const totals = computed(() => {
  const out: Record<string, number> = {};
  for (const snap of [q1.value, q2.value]) {
    for (const t of snap?.tally ?? []) out[t.optionId] = (out[t.optionId] ?? 0) + t.count;
  }
  return out;
});
</script>

<template>
  <ul>
    <li v-for="(count, id) in totals" :key="id">{{ id }}: {{ count }}</li>
  </ul>
</template>
```

If you omit `name`, the panel registers under its `slug` (or `slug::questionId`
when several questions share a slug) — handy for one-off lookups, but a `name`
keeps aggregator slides free of UUIDs.

The cache is mirrored to `localStorage` (`slidev-polls:results-cache`), so the
last-known tally is available immediately on slide nav before the SSE stream
re-opens. The store only contains polls whose `PollResults` component has
mounted at least once in the current browser; there is no eager pre-fetch.
