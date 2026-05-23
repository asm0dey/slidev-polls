CREATE TABLE poll_collaborators (
    poll_id    uuid        NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    username   text        NOT NULL REFERENCES admin_user(username) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (poll_id, username)
);
CREATE INDEX idx_poll_collaborators_username ON poll_collaborators(username);
