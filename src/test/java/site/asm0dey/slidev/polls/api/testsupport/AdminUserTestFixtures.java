package site.asm0dey.slidev.polls.api.testsupport;

import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import org.jooq.DSLContext;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;

/**
 * Test-only fixtures for the admin_user table. The production seed migration was removed in V6, so
 * every test that needs a logged-in presenter calls {@link #ensureAdmin} (or {@link #seedAdmin}) to
 * populate the table.
 *
 * <p>Argon2 with the production parameters (m=65536, t=3, p=4) takes 150-250ms per encode on a
 * modern CPU; reuse the fixture across a class to keep suites fast. Hashing goes through the
 * production {@link Argon2PasswordHasher} so the row is verifiable by {@code
 * AdminPasswordIdentityProvider} at login time.
 */
public final class AdminUserTestFixtures {

  private AdminUserTestFixtures() {}

  public static void seedAdmin(
      DSLContext dsl, Argon2PasswordHasher hasher, String username, String password) {
    String hash = hasher.encode(password);
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, username)
        .set(ADMIN_USER.PASSWORD_HASH, hash)
        .execute();
  }

  /**
   * Idempotent upsert variant for ITs that create polls referencing the seeded admin via FK
   * (polls_owner_username_fk). The upsert guarantees the row exists with the requested password
   * hash even if a sibling test already inserted the same username with a different password.
   */
  public static void ensureAdmin(
      DSLContext dsl, Argon2PasswordHasher hasher, String username, String password) {
    String hash = hasher.encode(password);
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, username)
        .set(ADMIN_USER.PASSWORD_HASH, hash)
        .onConflict(ADMIN_USER.USERNAME)
        .doUpdate()
        .set(ADMIN_USER.PASSWORD_HASH, hash)
        .execute();
  }
}
