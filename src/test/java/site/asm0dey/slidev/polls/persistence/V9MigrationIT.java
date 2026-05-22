package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V9 migrates the polls.allowed_origins array column into the poll_allowed_origins child table and
 * drops the column. Ported to {@code @QuarkusTest}: a throwaway database on the Dev Services
 * Postgres server (via {@link #freshPgDatabase()}) replaces the prior Testcontainers Postgres.
 */
@QuarkusTest
class V9MigrationIT extends AbstractPostgresTest {

  @Test
  void origins_array_migrates_into_child_table() throws Exception {
    DataSource ds = freshPgDatabase();
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration/postgresql")
        .target("8")
        .load()
        .migrate();
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("INSERT INTO admin_user(username, password_hash) VALUES ('b','x')");
      s.execute(
          "INSERT INTO polls(id, owner_username, title, slug, status, allowed_origins) "
              + "VALUES (gen_random_uuid(),'b','t','test-slug','DRAFT', "
              + "ARRAY['https://a.example','https://b.example'])");
    }
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration/postgresql")
        .load()
        .migrate();
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      var rs = s.executeQuery("SELECT count(*) FROM poll_allowed_origins");
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(2);
      // allowed_origins column is gone
      rs =
          s.executeQuery(
              "SELECT count(*) FROM information_schema.columns "
                  + "WHERE table_name='polls' AND column_name='allowed_origins'");
      rs.next();
      assertThat(rs.getInt(1)).isZero();
    }
  }
}
