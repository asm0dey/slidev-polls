-- Drops the publicly-known seeded presenter (alice / correct-horse) and the
-- polls she owns. Cascading FKs on poll_questions, poll_options, poll_votes,
-- deck_tokens, and poll_allowed_origins remove the dependent rows. Operators
-- whose installs accumulated real data under alice must export-and-reimport
-- before applying this migration; the seed was a public hash and any data
-- behind it was effectively unprotected anyway.
--
-- Renames bcrypt_hash to password_hash because v1.4 switches to Argon2id;
-- the column carries the encoded hash string (Modular Crypt Format) which
-- self-describes its algorithm and parameters.

DELETE FROM polls WHERE owner_username = 'alice';
DELETE FROM admin_user WHERE username = 'alice';
ALTER TABLE admin_user RENAME COLUMN bcrypt_hash TO password_hash;
