# Phase 0 Research: Presenter Auth Gating in Slidev Deck

## Decision Log

### D-001 — Where the auth control is rendered in the deck

**Decision**: Render the auth control through Slidev's `global-top.vue`
overlay file, packaged inside the `@polls/slidev-addon` addon. The
control lives at the addon root and is auto-discovered by Slidev the
same way the built-in global layers are.

**Rationale**: Slidev documents exactly three slots for persistent
overlays that render on every slide — `global-top.vue`,
`global-bottom.vue`, and `custom-nav-controls.vue`. Only
`global-top.vue` sits above every slide (including presenter mode) as
an absolute-positioned overlay the addon can style independently of
the theme; `custom-nav-controls.vue` lives inside the hide-on-idle
nav bar, which is presenter-mode-biased and vanishes during the talk
exactly when we want the auth state to be visible to the presenter.
Addons that ship these files at their root are overlaid onto the
user's project by Slidev without configuration, which matches
Principle V — no new plumbing, no new config knob.

**Alternatives considered**:

- **Custom nav controls** — rejected: the navigation bar hides on
  idle in the viewer view and in presenter mode's slide area, so the
  visible-state requirement of FR-002 would not hold.
- **A Vue component the presenter must place inside their markdown** —
  rejected: violates the "auth control reachable from every slide"
  clause of FR-001 (any slide where the presenter forgot the
  component would drop them to anonymous), and invites the same
  "paste this into the deck" footgun that today's `deckToken` prop
  created.
- **Inject through Slidev setup hooks only** — rejected: `setup/`
  files tune config/menu plumbing; they cannot directly mount visible
  UI. Mixing them with an overlay file gains nothing.

**Source**: Slidev docs, "Global Layers" and "Writing Addons >
Publishing" (fetched via Context7 `/websites/sli_dev`, 2026-04-21).

---

### D-002 — Credential storage on the deck client

**Decision**: Persist the deck token plaintext in `localStorage` under
key `slidev-polls:deck-auth` as a JSON object
`{ token, tokenId, pollId, label, verifiedAt }`. The token plaintext
is held only on that client; the backend continues to persist only
its SHA-256 hash (unchanged from 001). Sign-out removes the key;
`"revoked"` state also removes it.

**Rationale**: `localStorage` satisfies FR-005 and SC-003 (state
restored across reloads without re-prompting) with no additional
plumbing. Same-origin is already a hard invariant from 001, so the
token does not leak across origins. No presenter-facing PII is stored
(the token *is* the credential, already treated as secret by the
backoffice mint flow). Cookies were rejected because the SPA and the
addon already share nothing with the backoffice session cookie, and
making the deck token a cookie would silently attach it to every
same-origin request — giving SSE connections and voter traffic an
accidental authenticated side-channel we explicitly do not want.

**Alternatives considered**:

- **`sessionStorage`** — rejected: wipes on tab close; fails SC-003.
- **Cookies (HttpOnly or not)** — rejected: creates the
  accidental-auth side-channel above; also HttpOnly blocks the JS
  composable from reading the token to send as the `X-Deck-Token`
  header.
- **IndexedDB** — rejected: overkill for a single string; brings no
  advantage over `localStorage` at this scale.

---

### D-003 — Validating a freshly-entered token

**Decision**: Add `GET /api/deck/auth/me` to the existing
`/api/deck/**` security-gated surface. Authenticated by the existing
`DeckTokenAuthenticationFilter`. Returns
`{ tokenId, pollId, label }` on 200; on missing/revoked bearer the
existing `ProblemAuthenticationEntryPoint` emits the standard
`Problem{code: DECK_TOKEN_INVALID}` 401 envelope.

**Rationale**: The existing activation endpoint
(`POST /api/deck/polls/{pollId}/activate`) requires a poll id *and*
mutates state; using it as a validator would (a) require the
composable to know a poll id before the presenter has reached any
slide, and (b) trigger a real activation side effect on the backend
— acceptable during normal navigation but not as a "did my paste
stick?" probe. A cheap, side-effect-free GET on a path the filter
already guards reuses every existing security primitive without
opening a new authorisation surface.

Adding `me` as the path segment mirrors the HTTP convention for
"what does my credential represent" and keeps us from inventing a
new verb.

**Alternatives considered**:

- **Reuse `POST .../activate` with a no-op question id** — rejected:
  dirty; either the controller rejects on invalid id (not actually a
  validation) or it produces unintended state changes.
- **Client-side base64 / JWT inspection** — rejected: the deck token
  is an opaque bearer (001 explicitly hashes it server-side); there's
  nothing the client can verify without the backend.
- **Validate lazily on the first activation POST** — rejected:
  violates the Story 1 acceptance criterion that sign-in be visually
  confirmed *before* the presenter navigates to a poll slide.

---

### D-004 — Revocation reaction

**Decision**: `<PollResults>` invokes `useDeckAuth().markRevoked()`
when its activation POST responds with an HTTP 401 whose body is
`Problem{code: DECK_TOKEN_INVALID}`. The composable flips to
`"revoked"`, wipes `localStorage`, and the auth control re-renders in
the not-signed-in visual with the FR-014 authentication-failure
message.

**Rationale**: The backoffice can revoke a deck token at any time
via the existing
`DELETE /api/admin/polls/{pollId}/deck-tokens/{tokenId}` — the same
mechanism feature 001 already exposes. The next activation attempt
naturally surfaces the revocation. Treating that 401 as the trigger
(as opposed to a separate polling / websocket "you were revoked"
signal) is the minimum viable reaction and matches SC-005 exactly:
"within one activation attempt of the revocation taking effect."

**Alternatives considered**:

- **Periodic re-validation via `/api/deck/auth/me`** — rejected:
  needless traffic; would only fire SC-005 *sooner*, not more
  reliably, and the spec's budget is exactly "one activation attempt."
- **An SSE "token-revoked" event** — rejected: doubles the
  revocation-notification path and requires an authenticated SSE
  channel (today's stream is public and slug-scoped). Not in scope.

---

### D-005 — What happens to the `deckToken` prop on `<PollResults>`

**Decision**: Delete the `deckToken` prop (and its cousins
`questionId` / `pollId` stay, since the slide still needs to declare
which question it's on). Slides that currently pass `:deckToken="…"`
are, by construction, the leak this feature closes — removing the
prop is the clean cut. A console warning fires in dev builds if the
prop is seen, pointing at this feature's ID.

**Rationale**: Principle V — one way to supply credentials (the auth
control), not two. Principle II/IV — no prop means no path where the
deck author can accidentally commit a credential into slide source
ever again.

**Alternatives considered**:

- **Keep the prop as a fallback** — rejected: reintroduces the exact
  leak this feature exists to prevent. The feature's value vanishes
  if a deck author can just paste the token back.
- **Keep the prop but only read it in tests** — rejected: bifurcates
  production/test contracts; violates "tests assert the production
  contract" implied by Principle III.

---

### D-006 — Reload-time verify policy

**Decision**: On composable init, if `localStorage` holds a token,
fire `GET /api/deck/auth/me` **in the background** (no await on
render path). The composable initialises in `"signed-in-pending"`,
flips to `"signed-in"` on 200 or `"revoked"` on 401. While
`"signed-in-pending"`, `<PollResults>` treats it as anonymous for the
activation decision — the deck renders read-only for the blink
between "reload happened" and "verify returned." This honours FR-007
(zero activation calls from an unverified browser) without blocking
render on a network hop, and is invisible in the happy path because
the verify completes well before the presenter navigates to the
first poll slide.

**Rationale**: Trading a sub-second read-only window for a stricter
"never activate without a currently-verified credential" rule
matches the spec's security posture.

**Alternatives considered**:

- **Treat persisted state as immediately signed-in** — rejected: if
  the backoffice revoked the token while the tab was closed, the
  first slide navigation would fire an activation that the backend
  refuses. SC-005 is still met, but the "zero activation calls from
  an unauthenticated browser" claim of FR-007 is violated for one
  round-trip.
- **Block render on verify** — rejected: FR-013 / Principle IV
  forbid blocking the deck on network state.

---

### D-007 — Slidev `global-top.vue` and SSR / presenter mode

**Decision**: The auth control is safe to render in both single-page
view and presenter mode because it (a) gates all browser API access
behind `onMounted` / `window` checks, and (b) lives inside the addon
where Slidev guarantees a Vue runtime before rendering. No special
handling for export / PDF mode is needed — the control is visible in
exports too, which matches the spec's "auth control is reachable
from every slide" intent; it's inert in exports because `fetch` is
no-op'd by the Slidev export runtime.

**Rationale**: Confirmed against Slidev docs for addon global layers
(2026-04-21). No project-specific work needed.

---

## Unknowns Resolved

- **Does Slidev auto-discover `global-top.vue` from an addon's
  package root?** Yes. Slidev overlays addon files onto the user's
  project at slide compilation time; `global-top.vue`,
  `global-bottom.vue`, `custom-nav-controls.vue`, and `setup/*.ts`
  files are the documented extension points. (Context7,
  `/websites/sli_dev`, 2026-04-21.)
- **Does the existing `DeckTokenAuthenticationFilter` support a
  request that doesn't name a poll in the path?** Yes — the filter
  keys off the `X-Deck-Token` header and populates a
  `DeckPrincipal(tokenId, pollId)` regardless of path; the
  controller is responsible for any poll-scope check. A plain
  authenticated route that does not name a poll in the URL is fine.
- **Does the existing `ProblemCode` enum cover the failure
  classifications needed by FR-014?** Yes —
  `DECK_TOKEN_INVALID`, `DECK_TOKEN_POLL_MISMATCH`, and
  `TRANSPORT_FAILURE` (the last a client-synthesised code for fetch
  failures) are sufficient.

## Tessl Tiles

Not applicable — no Tessl tile is installed for Slidev or Vue, and
no new technology is being selected. Backend and frontend tech stack
are unchanged from 001.
