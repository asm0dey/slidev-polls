package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V8 drops the polls.style column. Ported to {@code @QuarkusTest}: a throwaway database on the Dev
 * Services Postgres server (via {@link #freshPgDatabase()}) replaces the prior Testcontainers
 * Postgres.
 */
@QuarkusTest
class V8MigrationIT extends AbstractPostgresTest {

  @Test
  void style_column_is_gone_after_V8() throws Exception {
    DataSource ds = freshPgDatabase();
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration/postgresql", "classpath:db/migration/common")
        .load()
        .migrate();
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      var rs =
          s.executeQuery(
              "SELECT count(*) FROM information_schema.columns "
                  + "WHERE table_name='polls' AND column_name='style'");
      rs.next();
      assertThat(rs.getInt(1)).isZero();
    }
  }
}
