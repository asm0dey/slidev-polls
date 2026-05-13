-- v1.5 retires the per-account display name. The lowercase username is the only
-- identifier surfaced in the backoffice UI now, so the column is dropped outright
-- — there is no down-conversion path because the original input was free-form
-- text the operator can re-enter if they ever want a separate label back.
--
-- No FK references admin_user.display_name (it was output-only), so the DROP is
-- non-cascading.

ALTER TABLE admin_user DROP COLUMN display_name;
