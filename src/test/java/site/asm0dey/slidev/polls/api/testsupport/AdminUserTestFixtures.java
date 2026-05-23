package site.asm0dey.slidev.polls.api.testsupport;

import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.DECK_TOKENS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_ALLOWED_ORIGINS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_COLLABORATORS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_OPTIONS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_QUESTIONS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.VOTES;

import org.jooq.DSLContext;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Test-only fixtures for the admin_user table. The production seed migration was removed in V6, so
 * every test that needs a logged-in presenter calls seedAdmin() in a @BeforeEach (or @BeforeAll for
 * slow Argon2 hashing) to populate the table.
 *
 * <p>Argon2 with the production parameters (m=65536, t=3, p=4) takes 150-250ms per encode on a
 * modern CPU. Reusing the fixture across a class via @BeforeAll keeps suites fast.
 */
public final class AdminUserTestFixtures {

  private AdminUserTestFixtures() {}

  /**
   * Wipes admin_user and every table that references it, in FK-safe order, for ITs that need to
   * start from an empty user table. A bare {@code delete from admin_user} fails whenever a poll
   * (polls_owner_username_fk) or deck token (deck_tokens.minted_by) left by a sibling IT still
   * references a user — the Spring-context ITs share one reused Testcontainers Postgres, so rows
   * leak across classes. Deleting the dependents first avoids that constraint violation.
   */
  public static void wipeAdminUsers(DSLContext dsl) {
    dsl.deleteFrom(DECK_TOKENS).execute();
    dsl.deleteFrom(VOTES).execute();
    dsl.deleteFrom(POLL_OPTIONS).execute();
    dsl.deleteFrom(POLL_QUESTIONS).execute();
    dsl.deleteFrom(POLL_ALLOWED_ORIGINS).execute();
    dsl.deleteFrom(POLL_COLLABORATORS).execute();
    dsl.deleteFrom(POLLS).execute();
    dsl.deleteFrom(ADMIN_USER).execute();
  }

  public static void seedAdmin(
      DSLContext dsl, PasswordEncoder encoder, String username, String password) {
    String hash = encoder.encode(password);
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, username)
        .set(ADMIN_USER.PASSWORD_HASH, hash)
        .execute();
  }

  /**
   * Idempotent upsert variant for ITs that create polls referencing the seeded admin via FK
   * (polls_owner_username_fk). Since polls from earlier tests may still own alice, deleting and
   * reinserting would either FK-fail or PK-collide. The upsert guarantees the row exists with the
   * requested password hash even if a sibling IT (e.g. AdminUserManagementIT seeding
   * "correct-horse-battery") has already inserted alice with a different password.
   */
  public static void ensureAdmin(
      DSLContext dsl, PasswordEncoder encoder, String username, String password) {
    String hash = encoder.encode(password);
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, username)
        .set(ADMIN_USER.PASSWORD_HASH, hash)
        .onConflict(ADMIN_USER.USERNAME)
        .doUpdate()
        .set(ADMIN_USER.PASSWORD_HASH, hash)
        .execute();
  }
}
