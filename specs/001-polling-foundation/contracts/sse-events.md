# SSE Event Contract — `GET /api/polls/{slug}/stream`

Complements `openapi.yaml`. The SSE endpoint is keyed by the poll's
**slug** (the same memorable identifier the voter reaches the poll
through). This file specifies the event types the server emits and the
guarantees the client may rely on.

## Transport

- `Content-Type: text/event-stream`.
- No authentication; the stream is scoped by `slug` only.
- Server-side, the per-subscriber hold-open is a `SseEmitter` tracked
  by `SseHub` keyed by `pollId` (not `slug`), so a slug rotation that
  happens mid-session does not drop subscribers. The client can
  continue to consume the same slug URL; if the slug is rotated the
  server MAY emit a terminal error and close the stream, in which
  case the client surfaces the "paused" indicator and stops retrying.
- The server MUST send a comment heartbeat (`: keep-alive`) at least
  every 20 seconds.
- Clients use the browser `EventSource` API and its built-in
  reconnection. On reconnect, the server MUST re-emit a `snapshot` so
  the client starts from a consistent state (Principle IV, SC-006).

## Event types

### `snapshot`

Emitted on connect, on reconnect, and whenever the active question
changes.

```json
{
  "pollId": "018f...",
  "slug": "my-poll",
  "activeQuestion": {
    "id": "018f...",
    "prompt": "Which JVM do you run in prod?",
    "ordinal": 1,
    "options": [
      { "id": "018f...opt-a", "label": "OpenJDK",  "position": 1 },
      { "id": "018f...opt-b", "label": "GraalVM",  "position": 2 }
    ]
  },
  "tally": [
    { "optionId": "018f...opt-a", "count": 12 },
    { "optionId": "018f...opt-b", "count":  4 }
  ],
  "emittedAt": "2026-04-19T10:15:23.123Z"
}
```

- `activeQuestion` is `null` when the poll has no active question
  (FR-008 "waiting" state).
- `tally` MUST include every option of the active question, even
  those with count 0.

### `tally`

Emitted on each accepted vote.

```json
{
  "pollId": "018f...",
  "questionId": "018f...",
  "optionId": "018f...opt-a",
  "count": 13,
  "emittedAt": "2026-04-19T10:15:24.987Z"
}
```

- `count` is the **new absolute tally** for that option, not a delta.
- `questionId` MUST match the `activeQuestion.id` from the most recent
  `snapshot`. If it does not, the client MUST ignore the event and
  wait for the next `snapshot`.

### `question-closed`

Emitted when the active question transitions to `CLOSED` with no
replacement.

```json
{
  "pollId": "018f...",
  "questionId": "018f...",
  "emittedAt": "2026-04-19T10:15:40.000Z"
}
```

A subsequent `snapshot` will follow when a new question becomes
active.

## Client behaviour (normative)

- On `EventSource.onerror` the client MUST NOT crash, MUST surface a
  visible "live updates paused" indicator, and MUST defer to the
  browser's automatic reconnection (FR-015, SC-006).
- On the first `snapshot` after reconnect the client MUST clear the
  "paused" indicator.
- Clients MUST NOT rely on message ordering beyond "a `snapshot`
  supersedes prior state for the same `pollId`." A client that
  applies `snapshot` authoritatively remains correct even if the
  preceding `tally` was processed out of order.
