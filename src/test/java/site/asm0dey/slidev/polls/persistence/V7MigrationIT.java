package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// V7 drops admin_user.display_name. The column was NOT NULL with no default; this
// migration removes it outright (no down-conversion, no shadow column). Run migrations
// to V6, assert the column is still present, run to V7, assert it is gone.
//
// information_schema.columns is not a jOOQ-generated table, so we use raw
// DSL.table/DSL.field with string literals. String literals are intentional here:
// the jOOQ-generated ADMIN_USER table will be dropped in Plan Task 3, and these
// names are stable identifiers independent of codegen.
@Testcontainers
class V7MigrationIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @Test
  void v7DropsDisplayNameColumn() {
    Flyway upToV6 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("6")
            .load();
    upToV6.migrate();

    try (var dsl =
        DSL.using(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      int preCount =
          dsl.fetchCount(
              DSL.table("information_schema.columns"),
              DSL.field("table_name")
                  .eq("admin_user")
                  .and(DSL.field("column_name").eq("display_name")));
      assertThat(preCount).as("display_name present at V6").isOne();

      Flyway upToV7 =
          Flyway.configure()
              .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
              .target("7")
              .load();
      upToV7.migrate();

      int postCount =
          dsl.fetchCount(
              DSL.table("information_schema.columns"),
              DSL.field("table_name")
                  .eq("admin_user")
                  .and(DSL.field("column_name").eq("display_name")));
      assertThat(postCount).as("display_name dropped at V7").isZero();
    }
  }
}
