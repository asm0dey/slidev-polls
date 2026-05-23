ALTER TABLE deck_tokens ADD COLUMN minted_by varchar(64);
UPDATE deck_tokens dt SET minted_by = (SELECT p.owner_username FROM polls p WHERE p.id = dt.poll_id) WHERE dt.minted_by IS NULL;
ALTER TABLE deck_tokens ALTER COLUMN minted_by SET NOT NULL;
ALTER TABLE deck_tokens ADD CONSTRAINT deck_tokens_minted_by_fk FOREIGN KEY (minted_by) REFERENCES admin_user(username);
