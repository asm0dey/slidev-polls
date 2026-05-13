-- Drop the denormalised columns on polls; canonical state lives entirely on poll_questions.
ALTER TABLE polls DROP CONSTRAINT polls_active_question_fk;
ALTER TABLE polls DROP COLUMN active_question_id;
ALTER TABLE polls DROP COLUMN status;

-- The single-statement CASE UPDATE in PollRepositoryImpl.activateQuestion flips two
-- poll_questions rows in a single statement: the previously-ACTIVE row to CLOSED and the
-- target row to ACTIVE. PostgreSQL evaluates the unique index per row update, so it would
-- (depending on heap scan order) see a transient duplicate on active_poll_id. Replace the
-- non-deferrable unique index with a DEFERRABLE INITIALLY DEFERRED unique constraint so the
-- check fires at statement-end, when the index is consistent again.
DROP INDEX poll_questions_one_active_uq;
ALTER TABLE poll_questions
    ADD CONSTRAINT poll_questions_one_active_uq UNIQUE (active_poll_id)
    DEFERRABLE INITIALLY DEFERRED;

-- Trigger that keeps polls.updated_at fresh when any poll_questions row on the poll changes.
CREATE OR REPLACE FUNCTION touch_poll_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE polls
       SET updated_at = now()
     WHERE id = COALESCE(NEW.poll_id, OLD.poll_id);
    RETURN NULL;
END;
$$;

CREATE TRIGGER poll_questions_touch_polls
AFTER INSERT OR UPDATE OR DELETE ON poll_questions
FOR EACH ROW EXECUTE FUNCTION touch_poll_updated_at();
