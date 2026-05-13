package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// V6 must (a) delete polls owned by alice (cascading children), (b) delete the alice
// admin_user row, (c) rename admin_user.bcrypt_hash to admin_user.password_hash.
// Verified by stopping migrations at V5, seeding alice + a poll, then running V6.
@Testcontainers
class V6MigrationIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @Test
  void v6DeletesSeedAliceWithOwnedPollsAndRenamesHashColumn() {
    Flyway upToV5 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration/postgresql", "classpath:db/migration/common")
            .target("5")
            .load();
    upToV5.migrate();

    try (var dsl =
        DSL.using(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      // Pre-V6 state: the bcrypt_hash column still exists on the V5 schema, but the
      // jOOQ-generated Tables.ADMIN_USER reflects the post-V6 schema (password_hash).
      // Raw SQL is the only option here; V3 already seeded alice so ON CONFLICT keeps
      // this idempotent against future seed-data moves.
      dsl.execute(
          "INSERT INTO admin_user (username, display_name, bcrypt_hash) VALUES ('alice', 'Alice',"
              + " '$2a$10$9CvgAtcz7/XAUwDSz/7Uuu7K85lbQAGe8EqYH8Wvh4auT.ZO1Siai') ON CONFLICT"
              + " (username) DO NOTHING");
      UUID pollId = UUID.randomUUID();
      OffsetDateTime now = OffsetDateTime.now();
      dsl.insertInto(POLLS)
          .set(POLLS.ID, pollId)
          .set(POLLS.OWNER_USERNAME, "alice")
          .set(POLLS.TITLE, "doomed")
          .set(POLLS.SLUG, "doomed")
          .set(POLLS.STATUS, "DRAFT")
          .set(POLLS.CREATED_AT, now)
          .set(POLLS.UPDATED_AT, now)
          .execute();

      Flyway upToV6 =
          Flyway.configure()
              .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
              .locations("classpath:db/migration/postgresql", "classpath:db/migration/common")
              .target("6")
              .load();
      upToV6.migrate();

      // After V6 runs, the schema matches jOOQ codegen — use the generated tables.
      int adminCount = dsl.fetchCount(ADMIN_USER, ADMIN_USER.USERNAME.eq("alice"));
      int pollCount = dsl.fetchCount(POLLS, POLLS.ID.eq(pollId));

      // Column-level metadata lives in information_schema, which jOOQ does not
      // generate sources for. Raw DSL.field/DSL.table is the standard escape hatch.
      int newColumnCount =
          dsl.fetchCount(
              DSL.table("information_schema.columns"),
              DSL.field("table_name")
                  .eq("admin_user")
                  .and(DSL.field("column_name").eq("password_hash")));
      int oldColumnCount =
          dsl.fetchCount(
              DSL.table("information_schema.columns"),
              DSL.field("table_name")
                  .eq("admin_user")
                  .and(DSL.field("column_name").eq("bcrypt_hash")));

      assertThat(adminCount).isZero();
      assertThat(pollCount).isZero();
      assertThat(newColumnCount).isOne();
      assertThat(oldColumnCount).isZero();
    }
  }
}
