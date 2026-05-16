-- 1. Replace poll_questions.type with min/max selection bounds.
ALTER TABLE poll_questions
    ADD COLUMN min_selections int NOT NULL DEFAULT 1,
    ADD COLUMN max_selections int NOT NULL DEFAULT 1;
ALTER TABLE poll_questions
    ADD CONSTRAINT poll_questions_selections_ck
    CHECK (0 <= min_selections
           AND min_selections <= max_selections
           AND max_selections >= 1);
ALTER TABLE poll_questions DROP COLUMN type;

-- 2. Migrate votes.option_id (uuid) -> votes.option_ids (uuid[]).
ALTER TABLE votes ADD COLUMN option_ids uuid[];
UPDATE votes SET option_ids = ARRAY[option_id];
ALTER TABLE votes ALTER COLUMN option_ids SET NOT NULL;

-- 3. Drop per-option index and the FK-cascade column.
DROP INDEX IF EXISTS votes_by_question_option_ix;
ALTER TABLE votes DROP COLUMN option_id;

-- 4. Add GIN to keep tally scans cheap (only meaningful at scale; harmless small).
CREATE INDEX votes_option_ids_gin ON votes USING GIN (option_ids);

-- The (question_id, voter_token) unique index on votes is unchanged; one row per voter still.
