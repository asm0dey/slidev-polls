-- FR-XXX: per-poll CORS allowlist. Each entry is an exact origin
-- (scheme + host + port) that is permitted to embed the poll
-- (e.g. a Slidev deck on http://localhost:3030 or
-- https://example.github.io). Empty array = same-origin only.

ALTER TABLE polls
    ADD COLUMN allowed_origins text[] NOT NULL DEFAULT '{}'::text[];

-- Reject blanks and obviously broken entries. Origin format is
-- "scheme://host[:port]" with no path/query/fragment.
-- PostgreSQL CHECK constraints cannot contain subqueries, so validation
-- is delegated to an IMMUTABLE helper function (callable from the constraint).
CREATE OR REPLACE FUNCTION polls_origins_valid(origins text[]) RETURNS boolean
    LANGUAGE sql IMMUTABLE CALLED ON NULL INPUT AS
$$
    SELECT COALESCE(bool_and(o ~ '^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$'), TRUE)
    FROM unnest(origins) AS o
$$;

ALTER TABLE polls
    ADD CONSTRAINT polls_allowed_origins_format_ck
    CHECK (polls_origins_valid(allowed_origins));
