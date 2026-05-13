ALTER TABLE polls DROP CONSTRAINT polls_active_question_fk;
ALTER TABLE polls DROP COLUMN active_question_id;
ALTER TABLE polls DROP COLUMN status;

CREATE TRIGGER poll_questions_touch_polls
AFTER INSERT, UPDATE, DELETE ON poll_questions
FOR EACH ROW
CALL "site.asm0dey.slidev.polls.persistence.h2.TouchPollUpdatedAtTrigger";
