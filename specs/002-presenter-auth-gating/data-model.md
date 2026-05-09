# Phase 1 Data Model: Presenter Auth Gating in Slidev Deck

This feature introduces **no new persisted entities**. The deck
token row in the `deck_tokens` table (from feature 001 Flyway
migration `V3__deck_tokens.sql`) is the authoritative record for
the presenter credential; nothing server-side needs to change.

Two client-side entities are introduced for completeness.

## Entities

### DeckAuthState (client-side, `localStorage`)

Lives in the deck browser. Persisted under the `localStorage` key
`slidev-polls:deck-auth`. Encoded as a single JSON object; never
leaves the origin.

| Field        | Type                                      | Nullable | Notes |
|--------------|-------------------------------------------|----------|-------|
| `token`      | string                                    | no       | Plaintext bearer pasted by the presenter; identical to what the addon sends as `X-Deck-Token`. |
| `tokenId`    | UUID                                      | no       | Returned by `GET /api/deck/auth/me`; exposed to the UI to render the "signed in as {label}" pill. |
| `pollId`     | UUID                                      | no       | The poll this token is scoped to; mirrors the server-side scope. |
| `label`      | string                                    | yes      | Human label the backoffice attached at mint time (may be empty). |
| `verifiedAt` | ISO-8601 timestamp                        | no       | Last time the backend accepted this token (response of `/api/deck/auth/me`). Used for observability only; no expiry logic. |

**Lifecycle**:

1. **Anonymous** — key absent from `localStorage`.
2. **Signed-in-pending** — composable read the key on init; a
   background `GET /api/deck/auth/me` is in flight. UI renders
   "checking…" on the control, `<PollResults>` behaves as anonymous.
3. **Signed-in** — verify returned 200; `verifiedAt` updated.
   Activation POSTs now carry the token.
4. **Revoked** — verify returned 401, OR an activation POST returned
   401 `DECK_TOKEN_INVALID`. The key is wiped from `localStorage`;
   the UI shows the FR-014 authentication-failure message; next
   sign-in attempt starts from "Anonymous".

Transitions are total — every 401 / network error has a defined
target state. No silent swallowing.

**Validation rules** (applied in `useDeckAuth()`):

- `token` MUST be a non-empty string of printable ASCII, trimmed.
  An empty string on paste is refused client-side and never reaches
  the backend.
- `token` length SHOULD be within the `[20, 512]` character window —
  deck tokens minted by 001 sit well inside that. A value outside
  that range is refused with the FR-014 authentication-failure
  message before a network call is attempted.

### DeckPrincipalView (server-side DTO, transient)

Response body of `GET /api/deck/auth/me`. Not a persisted entity;
listed here so the contracts phase can reference it.

| Field     | Type   | Nullable | Notes |
|-----------|--------|----------|-------|
| `tokenId` | UUID   | no       | `deck_tokens.id`. |
| `pollId`  | UUID   | no       | `deck_tokens.poll_id`. |
| `label`   | string | yes      | `deck_tokens.label`. |

## Relationships

- `DeckAuthState` (client) ↔ `DeckPrincipalView` (server): the
  client derives `tokenId`, `pollId`, and `label` from a
  `DeckPrincipalView` response. There is no back-reference —
  `DeckAuthState` never round-trips to the server; only the bearer
  `token` does, in the `X-Deck-Token` header.
- `DeckAuthState.token` ↔ `deck_tokens.hash` (001): the server
  matches the plaintext against the stored SHA-256 hash inside
  `DeckTokenService.resolveLive(plaintext)`. Unchanged from 001.

## State Transitions

```
      ┌──────────────┐   paste + verify 200    ┌────────────┐
      │  Anonymous   │ ───────────────────────▶│  Signed-in │
      │ (no storage) │                          │            │
      │              │◀────────────────────────│            │
      │              │        sign-out /        │            │
      │              │    verify 401 / 401 on   │            │
      │              │       activation         │            │
      └──────┬───────┘                          └─────┬──────┘
             │                                        │
             │ reload tab, storage present            │ reload tab, storage present
             ▼                                        │
  ┌─────────────────────┐  verify 200                 │
  │ Signed-in-pending   │ ───────────────────────────▶│
  │ (storage loaded,    │                             │
  │  verify in flight)  │  verify 401 ────────────▶ Anonymous
  └─────────────────────┘
```

## Out of Scope for the Data Model

- **Per-device session identifiers / device naming** — sign-in is
  per browser instance; the per-device footnote in the spec is the
  same as in 001 (two browsers signed in = two presenters; no
  backend tracking beyond the shared deck token).
- **Expiry / TTL on `DeckAuthState`** — none. Revocation is the only
  way out of "signed-in", matching the backoffice revocation surface
  in 001.
- **Audit log of activations** — already covered by 001's
  `poll_activation_events` / correlation-id tracing. This feature
  adds no new audit event.
