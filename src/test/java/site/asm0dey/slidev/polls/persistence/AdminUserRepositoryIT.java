package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;

import java.time.Instant;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// Storage-level coverage for the admin_user table after V6: PASSWORD_HASH replaces BCRYPT_HASH and
// there is no seeded alice. Parametrised over PostgreSQL and H2. Each test wipes admin_user (and
// the polls that FK into it) first so sibling ITs sharing the JVM-wide Postgres container don't
// pollute the state.
class AdminUserRepositoryIT extends AbstractPostgresTest {

  abstract class CommonAdminUser {
    protected abstract DSLContext dsl();

    @BeforeEach
    void cleanAdminUser() {
      // Portable equivalent of "TRUNCATE admin_user CASCADE": drop dependent polls first
      // (which cascades to questions, options, votes, ...), then admin_user itself. H2 has
      // no TRUNCATE ... CASCADE syntax, so use plain DELETEs that work on both engines.
      DSLContext dsl = dsl();
      dsl.deleteFrom(POLLS).execute();
      dsl.deleteFrom(ADMIN_USER).execute();
    }

    @Test
    void countReturnsZeroOnEmptyTable() {
      AdminUserRepositoryImpl repo = new AdminUserRepositoryImpl(dsl());
      assertThat(repo.count()).isZero();
    }

    @Test
    void insertThenListAllReturnsInsertedRow() {
      AdminUserRepositoryImpl repo = new AdminUserRepositoryImpl(dsl());
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
      AdminUserRepositoryImpl repo = new AdminUserRepositoryImpl(dsl());
      repo.insert("alice", "$argon2id$...");

      assertThat(repo.existsByUsername("alice")).isTrue();
      assertThat(repo.existsByUsername("bob")).isFalse();
    }

    @Test
    void findPasswordHashReturnsStoredHash() {
      AdminUserRepositoryImpl repo = new AdminUserRepositoryImpl(dsl());
      repo.insert("alice", "$argon2id$abc");

      assertThat(repo.findPasswordHash("alice")).contains("$argon2id$abc");
      assertThat(repo.findPasswordHash("nope")).isEmpty();
    }
  }

  @Nested
  class OnPostgres extends CommonAdminUser {
    @Override
    protected DSLContext dsl() {
      return AbstractPostgresTest.dsl();
    }
  }

  @Nested
  class OnH2 extends CommonAdminUser {
    private final DataSource ds = AbstractH2Test.freshH2();

    @Override
    protected DSLContext dsl() {
      return AbstractH2Test.dsl(ds);
    }
  }
}
