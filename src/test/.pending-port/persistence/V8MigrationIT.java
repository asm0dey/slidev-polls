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
class V8MigrationIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @Test
  void style_column_is_gone_after_V8() throws Exception {
    DataSource ds = ds();
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

  private static DataSource ds() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }
}
