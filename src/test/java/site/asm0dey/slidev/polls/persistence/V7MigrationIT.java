package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/**
 * V7 drops admin_user.display_name. The column was NOT NULL with no default; this migration removes
 * it outright (no down-conversion, no shadow column). Run migrations to V6, assert the column is
 * still present, run to V7, assert it is gone.
 *
 * <p>Ported to {@code @QuarkusTest}: a throwaway database on the Dev Services Postgres server (via
 * {@link #freshPgDatabase()}) replaces the prior Testcontainers Postgres.
 */
@QuarkusTest
class V7MigrationIT extends AbstractPostgresTest {

  @Test
  void v7DropsDisplayNameColumn() {
    DataSource ds = freshPgDatabase();
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration/postgresql", "classpath:db/migration/common")
        .target("6")
        .load()
        .migrate();

    var dsl = DSL.using(ds, org.jooq.SQLDialect.POSTGRES);
    {
      int preCount =
          dsl.fetchCount(
              DSL.table("information_schema.columns"),
              DSL.field("table_name")
                  .eq("admin_user")
                  .and(DSL.field("column_name").eq("display_name")));
      assertThat(preCount).as("display_name present at V6").isOne();

      Flyway.configure()
          .dataSource(ds)
          .locations("classpath:db/migration/postgresql", "classpath:db/migration/common")
          .target("7")
          .load()
          .migrate();

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
