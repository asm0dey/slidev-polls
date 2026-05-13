-- Portability pass. Replaces every PG-only schema element with a shape both
-- PostgreSQL and H2 accept. The H2 baseline (db/migration/h2/V1) ships this
-- same end-state directly.

-- 1. polls.allowed_origins (text[]) → poll_allowed_origins child table.
CREATE TABLE poll_allowed_origins (
    poll_id   uuid NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    origin    varchar(255) NOT NULL,
    position  int NOT NULL,
    PRIMARY KEY (poll_id, origin)
);
CREATE INDEX poll_allowed_origins_by_poll_ix ON poll_allowed_origins (poll_id, position);

INSERT INTO poll_allowed_origins (poll_id, origin, position)
SELECT p.id, o.origin, o.ord - 1
FROM polls p,
     LATERAL unnest(p.allowed_origins) WITH ORDINALITY AS o(origin, ord);

ALTER TABLE polls DROP CONSTRAINT polls_allowed_origins_format_ck;
DROP FUNCTION polls_origins_valid(text[]);
ALTER TABLE polls DROP COLUMN allowed_origins;

-- 2. Functional unique index on lower(slug) → generated column + plain unique.
DROP INDEX polls_slug_lower_uq;
ALTER TABLE polls
    ADD COLUMN slug_lower varchar(40) GENERATED ALWAYS AS (lower(slug)) STORED;
CREATE UNIQUE INDEX polls_slug_lower_uq ON polls (slug_lower);

-- 3. Partial unique index (WHERE status='ACTIVE') → generated column with NULL
-- gap. Unique allows many NULLs on both PG and H2, so at most one ACTIVE
-- question per poll is still enforced.
DROP INDEX poll_questions_one_active_uq;
ALTER TABLE poll_questions
    ADD COLUMN active_poll_id uuid
        GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN poll_id END) STORED;
CREATE UNIQUE INDEX poll_questions_one_active_uq
    ON poll_questions (active_poll_id);

-- 4. Partial perf index on deck_tokens (WHERE revoked_at IS NULL) →
-- plain (poll_id, revoked_at) index. The planner still uses it for
-- "active tokens for poll X" lookups.
DROP INDEX deck_tokens_by_poll_ix;
CREATE INDEX deck_tokens_by_poll_ix ON deck_tokens (poll_id, revoked_at);
