-- polls.style was a free-form jsonb theme map. No frontend ever consumed it
-- (audited 2026-05-13). Drop the column entirely so V9's portability pass
-- has fewer PG-specific shapes to translate.

ALTER TABLE polls DROP COLUMN style;
