package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.persistence.jooq.Tables;

// Storage-level coverage for the admin_user table after V6: PASSWORD_HASH replaces
// BCRYPT_HASH and there is no seeded alice. Each test wipes admin_user first to stay
// independent of sibling ITs that share the JVM-wide Postgres container.
class AdminUserRepositoryIT extends AbstractPostgresTest {

  @BeforeEach
  void cleanAdminUser() {
    // CASCADE through polls (FK admin_user) and its dependents so sibling ITs that
    // seed an owner row don't block deletion.
    dsl().execute("truncate table " + Tables.ADMIN_USER.getName() + " cascade");
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
