package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Storage-level coverage for the admin_user table after V6: PASSWORD_HASH replaces BCRYPT_HASH and
 * there is no seeded alice. Parametrised over PostgreSQL (the application's injected
 * {@code @Default} {@link DSLContext} on Dev Services Postgres) and H2 (a self-contained raw-H2
 * {@link DSLContext} from {@link AbstractH2Test}).
 *
 * <p>The shared test bodies live on {@link Base} and run once per {@code @Nested} engine. Each body
 * starts by wiping admin_user (and the polls that FK into it) so the JVM-wide Dev Services Postgres
 * isn't polluted by sibling ITs sharing the container; each H2 nested test gets a brand-new
 * in-memory DB anyway, so the wipe is a no-op there.
 */
@QuarkusTest
class AdminUserRepositoryIT {

  @Inject DSLContext pgDsl;

  abstract class Base {

    abstract DSLContext dsl();

    @Test
    void countReturnsZeroOnEmptyTable() {
      DSLContext dsl = dsl();
      clean(dsl);
      assertThat(new AdminUserRepositoryImpl(dsl).count()).isZero();
    }

    @Test
    void insertThenListAllReturnsInsertedRow() {
      DSLContext dsl = dsl();
      clean(dsl);
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
    void existsByUsernameIsTrueAfterInsertAndFalseOtherwise() {
      DSLContext dsl = dsl();
      clean(dsl);
      AdminUserRepositoryImpl repo = new AdminUserRepositoryImpl(dsl);
      repo.insert("alice", "$argon2id$...");

      assertThat(repo.existsByUsername("alice")).isTrue();
      assertThat(repo.existsByUsername("bob")).isFalse();
    }

    @Test
    void findPasswordHashReturnsStoredHash() {
      DSLContext dsl = dsl();
      clean(dsl);
      AdminUserRepositoryImpl repo = new AdminUserRepositoryImpl(dsl);
      repo.insert("alice", "$argon2id$abc");

      assertThat(repo.findPasswordHash("alice")).contains("$argon2id$abc");
      assertThat(repo.findPasswordHash("nope")).isEmpty();
    }
  }

  @Nested
  class Postgres extends Base {
    @Override
    DSLContext dsl() {
      return pgDsl;
    }
  }

  @Nested
  class H2 extends Base {
    @Override
    DSLContext dsl() {
      return AbstractH2Test.dsl(AbstractH2Test.freshH2());
    }
  }

  // Portable equivalent of "TRUNCATE admin_user CASCADE": drop dependent polls first (which
  // cascades to questions, options, votes, ...), then admin_user itself.
  private static void clean(DSLContext dsl) {
    dsl.deleteFrom(POLLS).execute();
    dsl.deleteFrom(ADMIN_USER).execute();
  }
}
