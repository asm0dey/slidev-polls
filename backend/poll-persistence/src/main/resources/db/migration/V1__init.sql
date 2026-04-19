-- Initial schema for Slidev Polls.
-- Owned by poll-persistence; jOOQ types are generated from these migrations.

CREATE TABLE polls (
    id                  uuid PRIMARY KEY,
    owner_username      text NOT NULL,
    title               text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    slug                text NOT NULL CHECK (
                            slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
                            AND char_length(slug) BETWEEN 3 AND 40
                        ),
    status              text NOT NULL CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    style               jsonb NOT NULL DEFAULT '{}'::jsonb,
    active_question_id  uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE poll_questions (
    id              uuid PRIMARY KEY,
    poll_id         uuid NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    prompt          text NOT NULL CHECK (char_length(prompt) BETWEEN 1 AND 500),
    type            text NOT NULL DEFAULT 'SINGLE_CHOICE',
    ordinal         int  NOT NULL,
    status          text NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
    activated_at    timestamptz,
    closed_at       timestamptz,
    CONSTRAINT poll_questions_active_timestamp_ck
        CHECK ((status = 'ACTIVE') = (activated_at IS NOT NULL))
);

CREATE INDEX poll_questions_by_poll_ordinal_ix ON poll_questions (poll_id, ordinal);

-- FR-004: at most one ACTIVE question per poll.
CREATE UNIQUE INDEX poll_questions_one_active_uq
    ON poll_questions (poll_id) WHERE status = 'ACTIVE';

ALTER TABLE polls
    ADD CONSTRAINT polls_active_question_fk
    FOREIGN KEY (active_question_id) REFERENCES poll_questions(id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE poll_options (
    id              uuid PRIMARY KEY,
    question_id     uuid NOT NULL REFERENCES poll_questions(id) ON DELETE CASCADE,
    label           text NOT NULL CHECK (char_length(label) BETWEEN 1 AND 200),
    position        int  NOT NULL
);

CREATE INDEX poll_options_by_question_position_ix
    ON poll_options (question_id, position);

CREATE TABLE votes (
    id              uuid PRIMARY KEY,
    poll_id         uuid NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    question_id     uuid NOT NULL REFERENCES poll_questions(id) ON DELETE CASCADE,
    option_id       uuid NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    voter_token     text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- FR-009: best-effort single vote per device per question.
CREATE UNIQUE INDEX votes_question_voter_uq ON votes (question_id, voter_token);
CREATE INDEX        votes_by_question_option_ix ON votes (question_id, option_id);
