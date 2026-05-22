package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

/**
 * Storage-level coverage for the admin_user table after V6: PASSWORD_HASH replaces BCRYPT_HASH and
 * there is no seeded alice. Parametrised over PostgreSQL (the application's injected
 * {@code @Default} {@link DSLContext} on Dev Services Postgres) and H2 (a self-contained raw-H2
 * {@link DSLContext} from {@link AbstractH2Test}).
 *
 * <p>Ported to {@code @QuarkusTest}: the {@code @Nested} engine subclasses were flattened into
 * per-engine test methods because Quarkus cannot CDI-instantiate {@code @Nested} inner classes.
 * Each Postgres test wipes admin_user (and the polls that FK into it) first so sibling ITs sharing
 * the JVM-wide Dev Services Postgres don't pollute the state; each H2 test gets a fresh in-memory
 * DB.
 */
@QuarkusTest
class AdminUserRepositoryIT {

  @Inject DSLContext pgDsl;

  private DSLContext h2() {
    DataSource ds = AbstractH2Test.freshH2();
    return AbstractH2Test.dsl(ds);
  }

  // Portable equivalent of "TRUNCATE admin_user CASCADE": drop dependent polls first (which
  // cascades to questions, options, votes, ...), then admin_user itself.
  private static void clean(DSLContext dsl) {
    dsl.deleteFrom(POLLS).execute();
    dsl.deleteFrom(ADMIN_USER).execute();
  }

  @Test
  void countReturnsZeroOnEmptyTable_postgres() {
    clean(pgDsl);
    assertThat(new AdminUserRepositoryImpl(pgDsl).count()).isZero();
  }

  @Test
  void countReturnsZeroOnEmptyTable_h2() {
    DSLContext dsl = h2();
    clean(dsl);
    assertThat(new AdminUserRepositoryImpl(dsl).count()).isZero();
  }

  @Test
  void insertThenListAllReturnsInsertedRow_postgres() {
    clean(pgDsl);
    insertThenListAllReturnsInsertedRow(pgDsl);
  }

  @Test
  void insertThenListAllReturnsInsertedRow_h2() {
    DSLContext dsl = h2();
    clean(dsl);
    insertThenListAllReturnsInsertedRow(dsl);
  }

  private void insertThenListAllReturnsInsertedRow(DSLContext dsl) {
    AdminUserRepositoryImpl repo = new AdminUserRepositoryImpl(dsl);
    repo.insert("alice", "$argon2id$v=19$m=65536,t=3,p=4$abc$def");

    var users = repo.listAll();

    assertThat(users)
        .singleElement()
        .satisfies(
            u -> {
              assertThat(u.username()).isEqualTo("alice");
              assertThat(u.createdAt()).isAfter(Instant.now().minusSeconds(60));
            });
  }

  @Test
  void existsByUsernameIsTrueAfterInsertAndFalseOtherwise_postgres() {
    clean(pgDsl);
    existsByUsername(pgDsl);
  }

  @Test
  void existsByUsernameIsTrueAfterInsertAndFalseOtherwise_h2() {
    DSLContext dsl = h2();
    clean(dsl);
    existsByUsername(dsl);
  }

  private void existsByUsername(DSLContext dsl) {
    AdminUserRepositoryImpl repo = new AdminUserRepositoryImpl(dsl);
    repo.insert("alice", "$argon2id$...");

    assertThat(repo.existsByUsername("alice")).isTrue();
    assertThat(repo.existsByUsername("bob")).isFalse();
  }

  @Test
  void findPasswordHashReturnsStoredHash_postgres() {
    clean(pgDsl);
    findPasswordHash(pgDsl);
  }

  @Test
  void findPasswordHashReturnsStoredHash_h2() {
    DSLContext dsl = h2();
    clean(dsl);
    findPasswordHash(dsl);
  }

  private void findPasswordHash(DSLContext dsl) {
    AdminUserRepositoryImpl repo = new AdminUserRepositoryImpl(dsl);
    repo.insert("alice", "$argon2id$abc");

    assertThat(repo.findPasswordHash("alice")).contains("$argon2id$abc");
    assertThat(repo.findPasswordHash("nope")).isEmpty();
  }
}
