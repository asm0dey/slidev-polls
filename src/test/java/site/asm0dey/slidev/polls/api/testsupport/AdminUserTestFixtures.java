package site.asm0dey.slidev.polls.api.testsupport;

import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

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
