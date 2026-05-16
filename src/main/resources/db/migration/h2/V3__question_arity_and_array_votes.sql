ALTER TABLE poll_questions
    ADD COLUMN min_selections int NOT NULL DEFAULT 1;
ALTER TABLE poll_questions
    ADD COLUMN max_selections int NOT NULL DEFAULT 1;
ALTER TABLE poll_questions
    ADD CONSTRAINT poll_questions_selections_ck
    CHECK (0 <= min_selections
           AND min_selections <= max_selections
           AND max_selections >= 1);
ALTER TABLE poll_questions DROP COLUMN type;

ALTER TABLE votes ADD COLUMN option_ids uuid ARRAY;
UPDATE votes SET option_ids = ARRAY[option_id];
ALTER TABLE votes ALTER COLUMN option_ids SET NOT NULL;

DROP INDEX IF EXISTS votes_by_question_option_ix;
ALTER TABLE votes DROP COLUMN option_id;
-- H2 has no GIN; the (question_id, voter_token) btree is sufficient for the volumes H2 ever sees in tests.
