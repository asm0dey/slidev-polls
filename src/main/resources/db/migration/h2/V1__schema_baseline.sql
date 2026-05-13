-- H2 schema baseline. Equivalent to running V1..V9 against PostgreSQL, written
-- in DDL that parses on H2 2.x. Validation that used PG regex CHECKs lives in
-- application code (OriginNormaliser, SlugValidator) so it stays uniform
-- across both engines.
--
-- Note: enum CHECK constraints use REGEXP_LIKE rather than the more natural
-- `status IN ('A','B','C')`. H2 2.4.240 has a bug where CHECK constraints
-- containing an IN-list with constant strings build a TreeSet whose
-- comparator is bound to the SessionLocal that ran the DDL. Once that
-- session closes (e.g. when Flyway finishes and returns the connection),
-- subsequent INSERTs from new sessions hit "The database has been closed
-- [90098-240]" while validating the constraint. REGEXP_LIKE avoids the
-- ConditionInConstantSet code path entirely.

CREATE TABLE admin_user (
    username        varchar(64) PRIMARY KEY CHECK (username = lower(username)),
    password_hash   varchar(255) NOT NULL,
    created_at      timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE polls (
    id                  uuid PRIMARY KEY,
    owner_username      varchar(64) NOT NULL REFERENCES admin_user(username),
    title               varchar(200) NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    slug                varchar(40)  NOT NULL CHECK (char_length(slug)  BETWEEN 3 AND 40),
    slug_lower          varchar(40)  GENERATED ALWAYS AS (lower(slug)),
    status              varchar(10)  NOT NULL CHECK (REGEXP_LIKE(status, '^(DRAFT|OPEN|CLOSED)$')),
    active_question_id  uuid,
    created_at          timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX polls_slug_lower_uq ON polls (slug_lower);

CREATE TABLE poll_questions (
    id              uuid PRIMARY KEY,
    poll_id         uuid NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    prompt          varchar(500) NOT NULL CHECK (char_length(prompt) BETWEEN 1 AND 500),
    type            varchar(32)  NOT NULL DEFAULT 'SINGLE_CHOICE',
    ordinal         int          NOT NULL,
    status          varchar(10)  NOT NULL CHECK (REGEXP_LIKE(status, '^(DRAFT|ACTIVE|CLOSED)$')),
    activated_at    timestamp with time zone,
    closed_at       timestamp with time zone,
    active_poll_id  uuid GENERATED ALWAYS AS
        (CASE WHEN status = 'ACTIVE' THEN poll_id END),
    CONSTRAINT poll_questions_active_timestamp_ck
        CHECK ((status = 'ACTIVE') = (activated_at IS NOT NULL))
);
CREATE INDEX poll_questions_by_poll_ordinal_ix ON poll_questions (poll_id, ordinal);
CREATE UNIQUE INDEX poll_questions_one_active_uq ON poll_questions (active_poll_id);

ALTER TABLE polls
    ADD CONSTRAINT polls_active_question_fk
    FOREIGN KEY (active_question_id) REFERENCES poll_questions(id);

CREATE TABLE poll_options (
    id              uuid PRIMARY KEY,
    question_id     uuid NOT NULL REFERENCES poll_questions(id) ON DELETE CASCADE,
    label           varchar(200) NOT NULL CHECK (char_length(label) BETWEEN 1 AND 200),
    position        int NOT NULL
);
CREATE INDEX poll_options_by_question_position_ix ON poll_options (question_id, position);

CREATE TABLE votes (
    id              uuid PRIMARY KEY,
    poll_id         uuid NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    question_id     uuid NOT NULL REFERENCES poll_questions(id) ON DELETE CASCADE,
    option_id       uuid NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    voter_token     varchar(128) NOT NULL,
    created_at      timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX votes_question_voter_uq    ON votes (question_id, voter_token);
CREATE INDEX        votes_by_question_option_ix ON votes (question_id, option_id);

CREATE TABLE deck_tokens (
    id          uuid PRIMARY KEY,
    poll_id     uuid NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    token_hash  varchar(128) NOT NULL,
    label       varchar(120),
    created_at  timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at  timestamp with time zone
);
CREATE UNIQUE INDEX deck_tokens_hash_uq    ON deck_tokens (token_hash);
CREATE INDEX        deck_tokens_by_poll_ix ON deck_tokens (poll_id, revoked_at);

CREATE TABLE poll_allowed_origins (
    poll_id   uuid NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    origin    varchar(255) NOT NULL,
    position  int NOT NULL,
    PRIMARY KEY (poll_id, origin)
);
CREATE INDEX poll_allowed_origins_by_poll_ix ON poll_allowed_origins (poll_id, position);
