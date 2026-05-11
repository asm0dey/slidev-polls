-- Case-insensitive uniqueness for slugs. Slugs are stored lowercase;
-- this index makes that invariant enforceable regardless.
CREATE UNIQUE INDEX polls_slug_lower_uq ON polls (lower(slug));
