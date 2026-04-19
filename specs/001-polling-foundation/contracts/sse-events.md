# SSE Event Contract — `/api/public/polls/{pollId}/stream`

Complements `openapi.yaml`. The SSE endpoint is described there; this
file specifies the event types the server emits and the guarantees the
client may rely on.

## Transport

- `Content-Type: text/event-stream`
- No authentication; the stream is scoped by `pollId` only.
- The server MUST send a comment heartbeat (`: keep-alive`) at least
  every 20 seconds so intermediaries do not silently close the
  connection.
- Clients (Slidev addon and respondent app) use the browser
  `EventSource` API and rely on its built-in reconnection. On each
  reconnect the server MUST re-emit a `snapshot` so the client starts
  from a consistent state (Principle IV, SC-006).

## Event types

### `snapshot`

Emitted on connect, on reconnect, and whenever the active question
changes. Carries the full current state a client needs to render.

```json
{
  "pollId": "018f...",
  "activeQuestion": {
    "id": "018f...",
    "prompt": "Which JVM do you run in prod?",
    "ordinal": 1,
    "options": [
      { "id": "018f...opt-a", "label": "OpenJDK",    "ordinal": 1 },
      { "id": "018f...opt-b", "label": "GraalVM",    "ordinal": 2 }
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
- `tally` entries MUST include every option of the active question,
  even those with count 0, so the client can render without a
  secondary lookup.

### `tally`

Emitted on each accepted response. A minimal delta that a subscribed
client can apply directly to the view seeded by the most recent
`snapshot`.

```json
{
  "pollId": "018f...",
  "questionId": "018f...",
  "optionId": "018f...opt-a",
  "count": 13,
  "emittedAt": "2026-04-19T10:15:24.987Z"
}
```

- `count` is the **new absolute tally** for that option, not a
  delta. This lets a late-joining or briefly-disconnected client
  self-correct without computing deltas.
- `questionId` MUST match the `activeQuestion.id` from the most recent
  `snapshot`. If a client receives a `tally` whose `questionId` does
  not match its current `activeQuestion`, it MUST ignore it and wait
  for the next `snapshot` — this is the correct behaviour when a
  client's view has raced ahead of an active-question swap.

### `question-closed`

Emitted when the active question transitions to `CLOSED` with no
replacement. The client MUST render the "waiting" state.

```json
{
  "pollId": "018f...",
  "questionId": "018f...",
  "emittedAt": "2026-04-19T10:15:40.000Z"
}
```

A subsequent `snapshot` will follow when a new question becomes
active, so clients do not need to poll.

## Client behaviour (normative)

- On `EventSource.onerror` the client MUST NOT crash, MUST surface a
  visible "live updates paused" indicator, and MUST defer to the
  browser's automatic reconnection (Principle IV, FR-015, SC-006).
- On the first `snapshot` after reconnect the client MUST clear the
  "paused" indicator.
- Clients MUST NOT assume message ordering beyond "a `snapshot`
  supersedes prior state for the same `pollId`." The server emits
  `snapshot`s on active-question change, so a client that applies
  `snapshot` authoritatively remains correct even if the preceding
  `tally` was processed out of order.
