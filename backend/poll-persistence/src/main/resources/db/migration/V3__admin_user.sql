-- Presenter accounts. Authoritative table for backoffice login.
-- v1 ships with a single seeded account; self-serve sign-up is out of scope.

CREATE TABLE admin_user (
    username        text PRIMARY KEY CHECK (username = lower(username)),
    display_name    text NOT NULL,
    bcrypt_hash     text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- polls.owner_username could not carry this FK in V1 because admin_user did
-- not exist yet. Add it now that the table is in place.
ALTER TABLE polls
    ADD CONSTRAINT polls_owner_username_fk
    FOREIGN KEY (owner_username)
    REFERENCES admin_user (username);

-- Seed a known presenter so the quickstart and the TS-001-family scenarios
-- in backoffice-authoring.feature can log in without an admin CLI.
-- pgcrypto's crypt(..., gen_salt('bf', 10)) produces a BCrypt-compatible
-- hash; Spring Security's BCryptPasswordEncoder validates regardless of the
-- per-install salt, so the literal password "correct-horse" keeps working.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO admin_user (username, display_name, bcrypt_hash) VALUES
    ('alice', 'Alice Presenter', crypt('correct-horse', gen_salt('bf', 10)));
