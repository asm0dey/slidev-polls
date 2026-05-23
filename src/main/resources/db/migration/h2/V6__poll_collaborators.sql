CREATE TABLE poll_collaborators (
    poll_id    uuid                     NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    username   varchar(64)              NOT NULL REFERENCES admin_user(username) ON DELETE CASCADE,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (poll_id, username)
);
CREATE INDEX idx_poll_collaborators_username ON poll_collaborators(username);
