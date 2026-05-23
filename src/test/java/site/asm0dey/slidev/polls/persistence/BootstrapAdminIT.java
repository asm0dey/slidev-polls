package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.core.service.AdminUserRepository;

class BootstrapAdminIT extends AbstractPostgresTest {

  private AdminUserRepository repository;
  private DSLContext dsl;

  @BeforeEach
  void setUp() {
    dsl = AbstractPostgresTest.dsl();
    repository = new AdminUserRepositoryImpl(dsl);
    dsl.deleteFrom(ADMIN_USER).execute();
  }

  private void insert(String username, OffsetDateTime createdAt) {
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, username)
        .set(ADMIN_USER.PASSWORD_HASH, "x")
        .set(ADMIN_USER.CREATED_AT, createdAt)
        .execute();
  }

  @Test
  void earliestCreatedAtIsBootstrapAdmin() {
    insert("bob", OffsetDateTime.parse("2026-01-02T00:00:00Z"));
    insert("alice", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    assertThat(repository.findBootstrapAdminUsername()).contains("alice");
  }

  @Test
  void tieBrokenByUsernameAscending() {
    OffsetDateTime t = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    insert("zara", t);
    insert("amy", t);
    assertThat(repository.findBootstrapAdminUsername()).contains("amy");
  }

  @Test
  void emptyTableHasNoBootstrapAdmin() {
    assertThat(repository.findBootstrapAdminUsername()).isEmpty();
  }

  @Test
  void updatePasswordHash_changesStoredHash() {
    insert("alice", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    repository.updatePasswordHash("alice", "newhash");
    String stored =
        dsl.select(ADMIN_USER.PASSWORD_HASH)
            .from(ADMIN_USER)
            .where(ADMIN_USER.USERNAME.eq("alice"))
            .fetchOne(ADMIN_USER.PASSWORD_HASH);
    assertThat(stored).isEqualTo("newhash");
  }
}
