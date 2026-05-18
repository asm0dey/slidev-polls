# Google Slides Poll Overlay — Browser Extension (Runtime Path)

**Status:** Draft — pending user approval
**Date:** 2026-05-18
**Scope:** Spec A of two. Spec B (Apps Script editor add-on for authoring bindings + QR insertion) is a separate spec to be written after this one ships.

## Goal

Deliver live audience-poll results overlaid on a Google Slides deck during present mode, without requiring presenters to leave Google Slides. Reuse the existing slidev-polls backend (poll model, SSE stream, deck-token auth). Match the audience-facing UX of the existing slidev addon: scan a QR code, vote on a phone, see tallies update live.

## Non-Goals

- Authoring UX inside Slides (binding editor, QR insert). Deferred to Spec B.
- Editing slide content from the extension.
- Modifying slide DOM in **edit** mode — only **present** mode is in scope.
- Hosting or proxying the Google Slides deck.
- Auto-discovery of polls: presenter manually pairs slides with polls (via Spec B, or by curl/SQL until Spec B ships).

## Background

The existing slidev addon (`frontends/slidev-component`) renders a `<PollResults>` Vue component on slidev slides. It auto-activates a question when the slide mounts (if signed-in with a deck token), opens an SSE connection to `/api/polls/{slug}/stream`, and renders bars live. A QR code points the audience at `/<slug>`.

Google Slides is the second target presentation tool. Its present mode is a cross-origin web page on `docs.google.com`. We cannot inject a Vue component into a slide canvas because Slides has no HTML embed element. The only practical mechanism for in-slide live overlay is a **browser extension** that injects DOM into the present-mode page.

## Architecture

Three pieces, one new + two changes:

- **Browser extension** (new) — MV3, Chrome + Firefox. Content script on `https://docs.google.com/presentation/d/*/present*`. Detects current slide id from URL, fetches binding, opens SSE, renders Shadow-DOM-rooted overlay. Fires `/api/deck/.../activate` when binding present and deck token configured.
- **Backend** (changes) — new `deck_slide_bindings` table + four endpoints + CORS surface for extension origins.
- **Shared** (no API change) — `@slidev-polls/shared` already exposes `api-client`, `sse-client`, types; extension consumes them.

```
┌──────────── Google Slides present DOM (docs.google.com) ────────────┐
│  ┌─────────────────── extension content script ──────────────────┐  │
│  │  reads window.location:  ?slide=<objectId>                     │  │
│  │  MutationObserver fallback for URL changes within SPA          │  │
│  │  fetch  GET  /api/decks/{deckId}/bindings/{slideId}            │  │
│  │  open   SSE  /api/polls/{slug}/stream                          │  │
│  │  POST   /api/deck/polls/{pollId}/activate  (X-Deck-Token)      │  │
│  │  render Shadow-DOM overlay (bars, question, QR optional)       │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌──────────────────────── Backend (Spring Boot) ───────────────────────┐
│  NEW   GET    /api/decks/{deckId}/bindings              (no auth)    │
│  NEW   GET    /api/decks/{deckId}/bindings/{slideId}    (no auth)    │
│  NEW   PUT    /api/decks/{deckId}/bindings/{slideId}    (deck token) │
│  NEW   DELETE /api/decks/{deckId}/bindings/{slideId}    (deck token) │
│  EXIST POST   /api/deck/polls/{pollId}/activate         (deck token) │
│  EXIST GET    /api/polls/{slug}/stream                  (SSE)        │
└──────────────────────────────────────────────────────────────────────┘
```

## Data Model

### `deck_slide_bindings`

| Column | Type | Notes |
|---|---|---|
| `deck_id` | `text` (PK part 1) | Google Slides presentation ID (e.g. `1ZjK...`). Opaque string from URL path segment. |
| `slide_object_id` | `text` (PK part 2) | Slide objectId (e.g. `p`, `g123abc.p1`). From `?slide=` query param in present URL. |
| `poll_id` | `uuid` (FK → `polls.id`) | Owning poll. |
| `question_id` | `uuid` (FK → `poll_questions.id`) | Question to activate on slide enter. Validated to belong to `poll_id`. |
| `name` | `text NULL` | Optional human label, e.g. `"q1"`. |
| `created_at` | `timestamptz` | Default `now()`. |
| `updated_at` | `timestamptz` | Default `now()`, on update set to `now()`. |

PK: `(deck_id, slide_object_id)`.

FK constraints with `ON DELETE CASCADE` from `polls`/`poll_questions` — deleting a poll cleans up bindings.

Cross-engine note: this project supports PostgreSQL and H2. Flyway migration goes under `db/migration/common/` (engine-agnostic SQL) if possible, otherwise paired files in `db/migration/postgresql/` and `db/migration/h2/`. UUID + text + timestamptz are supported in both with no syntax divergence.

### Trust model for `deck_id`

`deck_id` is the Slides presentation ID, taken from the URL by the presenter (or extracted by the extension automatically). It is **opaque and high-entropy** — anyone with the URL can already view the deck (modulo Google's own ACL). We treat `deck_id` as a capability for reads: knowing `deck_id` lets you fetch its bindings. Writes still require a deck token.

The only data exposed via the capability is `{pollId, questionId, name}` — not vote counts, not voter IDs. Same risk profile as the existing public `/<slug>` voter route.

## Backend Endpoints

### `GET /api/decks/{deckId}/bindings`

Public. Returns the list of bindings for the deck.

```json
[
  {
    "slideObjectId": "g123.p1",
    "pollId": "bba8158b-ee31-432c-9160-d49b9f0a3655",
    "questionId": "3edc96d0-35f2-4c6f-99c8-d7de3f0d3680",
    "slug": "demo-poll",
    "name": "q1"
  }
]
```

`slug` is denormalised from `polls.slug` for the extension's convenience (SSE URL uses slug, activate uses pollId). Empty array if deck has no bindings (200, not 404).

### `GET /api/decks/{deckId}/bindings/{slideObjectId}`

Public. Returns a single binding or `404` if absent. Same shape as one element above.

### `PUT /api/decks/{deckId}/bindings/{slideObjectId}`

Deck-token auth (header `X-Deck-Token` — matches existing convention). Upsert.

Request:
```json
{ "pollId": "...", "questionId": "...", "name": "q1" }
```

Validation:
- `pollId` exists.
- `questionId` belongs to `pollId`.
- Deck token must be associated with `pollId` via existing `deck_tokens` table (same check that gates `/api/deck/polls/{pollId}/activate`).

Returns `200` with the stored row (same shape as `GET`).

### `DELETE /api/decks/{deckId}/bindings/{slideObjectId}`

Deck-token auth. Returns `204`.

### Activate path — unchanged

Extension calls `POST /api/deck/polls/{pollId}/activate` with `X-Deck-Token`. No backend change.

## CORS

The extension's `fetch` and `EventSource` originate from one of:
- `chrome-extension://<extension-id>`
- `moz-extension://<uuid>`
- *No* `https://docs.google.com` origin — content scripts use the extension's own origin for XHR/fetch in MV3.

Existing per-poll `Allowed origins` list (used by slidev) is wrong shape here: the extension origin is the same for any deck the user opens. Add a **global allowlist** read from `application.yml`:

```yaml
slidev-polls:
  global-allowed-origins:
    - chrome-extension://kpehjphbeegoaipmebafhjmijfbeglmh   # prod chrome ext id
    - moz-extension://*                                       # firefox: per-install uuid
```

Wildcard `moz-extension://*` is acceptable because the surface protected (read-only public bindings + SSE + activate-with-token) is not origin-secret-bearing — the deck token in `X-Deck-Token` is the actual write authority. Document the trade-off explicitly in `application.yml` comments.

Pinning the Chrome extension ID requires `key` field in the extension manifest (generated from a private signing key checked into `frontends/gslides-extension/keys/` — ignored from git, committed pub portion only). For dev, allow `chrome-extension://*` via a `dev` Spring profile.

## Browser Extension

### File layout

```
frontends/gslides-extension/
  manifest.json                       # MV3
  src/
    content/
      index.ts                        # entrypoint; URL watch + binding fetch + render
      slide-watcher.ts                # detects current slide id (URL + MutationObserver)
      overlay.ts                      # Shadow-DOM overlay; bars + question text
      sse.ts                          # thin wrapper around @slidev-polls/shared/sse-client
    options/
      options.html                    # backend URL + deck token form
      options.ts
    background/
      service-worker.ts               # minimal; storage proxy for options page <-> content
    styles/
      overlay.css                     # injected into Shadow DOM
  test/
    slide-watcher.test.ts
    overlay.test.ts                   # happy-dom + Shadow DOM
  vite.config.ts                      # MV3 build via vite-plugin-web-extension
  package.json                        # workspace member; depends on @slidev-polls/shared
  keys/
    .gitignore                        # ignore private key
    public.pem                        # public half (for Chrome ID pinning)
  README.md                           # install instructions (sideload + AMO link)
```

### Manifest (MV3, single source for both browsers)

```json
{
  "manifest_version": 3,
  "name": "Slidev Polls overlay",
  "version": "0.0.1",
  "description": "Live audience poll results overlaid on Google Slides during present mode.",
  "permissions": ["storage"],
  "host_permissions": [
    "https://docs.google.com/presentation/*"
  ],
  "optional_host_permissions": ["*://*/*"],
  "content_scripts": [
    {
      "matches": ["https://docs.google.com/presentation/d/*/present*"],
      "js": ["dist/content/index.js"],
      "css": [],
      "run_at": "document_idle"
    }
  ],
  "options_ui": {
    "page": "dist/options/options.html",
    "open_in_tab": true
  },
  "background": {
    "service_worker": "dist/background/service-worker.js",
    "type": "module"
  },
  "browser_specific_settings": {
    "gecko": {
      "id": "slidev-polls-overlay@asm0dey.site",
      "strict_min_version": "115.0"
    }
  },
  "key": "<base64 pinned pubkey for stable Chrome ID>"
}
```

`optional_host_permissions: ["*://*/*"]` covers the **backend** origin — at runtime the extension requests permission for the configured backend URL. This avoids hard-coding `http://localhost:8080` and lets the same extension target prod backends.

### Slide-watcher

Google Slides present mode keeps the same tab and updates the URL query as the presenter advances. The URL pattern is `https://docs.google.com/presentation/d/{deckId}/present?slide=id.{slideObjectId}`.

```ts
// slide-watcher.ts
export type SlideChange = { deckId: string; slideObjectId: string };

export function watchSlide(cb: (s: SlideChange) => void): () => void {
  let last = '';
  const tick = () => {
    const url = new URL(location.href);
    const m = url.pathname.match(/\/presentation\/d\/([^/]+)\//);
    const deckId = m?.[1];
    const slideObjectId = url.searchParams.get('slide')?.replace(/^id\./, '') ?? null;
    if (!deckId || !slideObjectId) return;
    const key = `${deckId}|${slideObjectId}`;
    if (key === last) return;
    last = key;
    cb({ deckId, slideObjectId });
  };
  tick();
  // Slides SPA-navigates; popstate covers most cases, MutationObserver on <title> covers the rest.
  window.addEventListener('popstate', tick);
  const obs = new MutationObserver(tick);
  obs.observe(document.querySelector('title')!, { childList: true });
  const poll = setInterval(tick, 500); // defensive fallback
  return () => {
    window.removeEventListener('popstate', tick);
    obs.disconnect();
    clearInterval(poll);
  };
}
```

Tests: feed synthetic `location.href` values + dispatch `popstate`, assert callback receives expected `SlideChange`s in order.

### Overlay (Shadow DOM)

Renders into a single `<div id="slidev-polls-overlay">` appended to `document.body`, with `attachShadow({mode:'closed'})`. CSS isolated from Slides' own rules. Positioned `fixed; bottom: 24px; right: 24px; width: 320px; pointer-events: none;` — only the dismiss button gets `pointer-events: auto`.

Contents: question prompt, bar chart of tally, "live" pulse dot, dismiss `×`, small "powered by slidev-polls" footer.

No interactivity beyond dismiss → keys never need to be captured; they reach the underlying Slides page and advance slides normally.

### Activate firing

When `slide-watcher` emits a `SlideChange`, content script:

1. `GET /api/decks/{deckId}/bindings/{slideObjectId}` (1 request, 200 or 404).
2. If 404: hide overlay, close any open SSE.
3. If 200 and deck token is configured: `POST /api/deck/polls/{pollId}/activate`.
4. Open SSE to `/api/polls/{slug}/stream` (close previous if open).
5. Render overlay on each `snapshot` event.

Failure modes:
- Backend unreachable: overlay shows "backend offline" once, stays muted until next slide change.
- Token rejected (401): overlay shows "deck token invalid — set in extension options"; activate not retried.
- SSE drops: overlay shows "live updates paused"; reconnect attempted with exponential backoff (1s, 2s, 4s, capped at 8s) for the duration of the slide.

### Options page

Plain HTML form:
- Backend URL (text)
- Deck token (password)
- "Test connection" button → `GET {backend}/api/health` (existing endpoint) → success/fail message.
- "Grant access to backend" button → `chrome.permissions.request({origins: [<backend>+'/*']})`.

Stored in `chrome.storage.local`. Content script reads via `chrome.storage.local.get`.

### Bundling

`vite-plugin-web-extension` produces a single `dist/` consumable by both Chrome (`chrome://extensions → Load unpacked → dist/`) and Firefox (`web-ext run --source-dir=dist`). `bun run build` produces both packages.

## Build, Test, Distribute

### Local dev

```
cd frontends/gslides-extension
bun install
bun run dev        # vite watch + reload via web-ext
```

Chrome sideload: `chrome://extensions → Developer mode → Load unpacked → frontends/gslides-extension/dist`.
Firefox sideload: `about:debugging → This Firefox → Load Temporary Add-on → frontends/gslides-extension/dist/manifest.json`.

### Tests

- **Unit (vitest)**: `slide-watcher.test.ts`, `overlay.test.ts`, `sse.test.ts`. Use happy-dom; mock `chrome.storage.local` and `fetch`/`EventSource`.
- **Backend (existing pattern)**: jOOQ + Testcontainers integration tests for `BindingRepository`, MockMvc tests for the four endpoints (success, validation failure, missing token, wrong token, cross-poll question).
- **Manual end-to-end checklist** (no headless Slides driver):
  1. Start backend (`task up`), create poll with two questions.
  2. Open a real Google Slides deck, copy URL deck ID, `curl -X PUT` to seed two bindings (one per slide).
  3. Install extension, set backend URL + deck token in options.
  4. Enter present mode, advance through slides, observe overlay updating and activate firing in backend logs.
  5. Vote from a second device against `/<slug>` to confirm tally updates.

### Distribution

v0.0.1: sideload only. README documents the unpacked-extension flow. Web Store / AMO submission deferred until UX stabilises.

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Google changes present-mode URL scheme | Defensive: keep slide-watcher in one file; cover with unit tests for current scheme; document the assumption at top of file. |
| MV3 service-worker restarts drop SSE | Non-issue — SSE lives in the **content script** (page-resident, persistent for the tab's lifetime). |
| Shadow-DOM CSS still leaks | Use `attachShadow({mode:'closed'})` and inline a `:host { all: initial }` reset. |
| CORS dev/prod mismatch | `dev` Spring profile allows `chrome-extension://*`; `prod` profile requires explicit ID list. |
| Slides advances slide before activate completes | Race is benign: activate is idempotent; out-of-order activates resolve to whichever slide is current after the dust settles. |
| Extension origin pinning requires private key custody | Generate once, commit pub key only, document key handling in `frontends/gslides-extension/keys/README.md`. v0.0.1 can ship unpinned; pinning lands before any non-localhost backend allowlist entry. |
| `optional_host_permissions: *://*/*` is broad | Acceptable: extension only uses it for the **user-configured** backend URL. Document in README. |

## Open Questions

None blocking. All listed risks have either a chosen mitigation or a documented acceptance.

## Out of Scope (Spec B preview)

Spec B will add an Apps Script editor add-on with:
- Backend URL + deck token form (UserProperties).
- Per-slide binding editor: pick poll + question, persist via the same `PUT /api/decks/{deckId}/bindings/{slideId}` endpoint defined here.
- "Insert QR" button: fetch QR image, place on current slide.
- "Test activate" button.

Spec B requires Spec A to be merged (consumes the bindings endpoints).
