ALTER TABLE deck_tokens ADD COLUMN minted_by text REFERENCES admin_user(username);
UPDATE deck_tokens dt SET minted_by = p.owner_username FROM polls p WHERE p.id = dt.poll_id;
ALTER TABLE deck_tokens ALTER COLUMN minted_by SET NOT NULL;
