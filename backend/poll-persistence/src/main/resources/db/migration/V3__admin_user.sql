CREATE TABLE admin_user (
    username        text PRIMARY KEY CHECK (username = lower(username)),
    display_name    text NOT NULL,
    bcrypt_hash     text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
