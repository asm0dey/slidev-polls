-- FR-019: presenter-minted, poll-scoped credential used by Slidev
-- decks to request auto-activation of a specific question (FR-018).

CREATE TABLE deck_tokens (
    id          uuid PRIMARY KEY,
    poll_id     uuid NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    token_hash  text NOT NULL,
    label       text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz
);

CREATE UNIQUE INDEX deck_tokens_hash_uq ON deck_tokens (token_hash);
CREATE INDEX        deck_tokens_by_poll_ix ON deck_tokens (poll_id) WHERE revoked_at IS NULL;
