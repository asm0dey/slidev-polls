package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class V9MigrationIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @Test
  void origins_array_migrates_into_child_table() throws Exception {
    DataSource ds = ds();
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

  private static DataSource ds() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }
}
