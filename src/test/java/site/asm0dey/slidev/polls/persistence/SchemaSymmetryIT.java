package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Asserts that the post-V9 PostgreSQL schema and the H2 V1 baseline have the same set of (table,
 * column, is_nullable, is_generated) tuples for every domain table jOOQ codegen includes. Drift
 * would silently break the H2 runtime because codegen runs against PG only.
 */
@Testcontainers
class SchemaSymmetryIT {

  private static final Set<String> TABLES =
      Set.of(
          "admin_user",
          "polls",
          "poll_questions",
          "poll_options",
          "poll_allowed_origins",
          "votes",
          "deck_tokens");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @Test
  void h2_baseline_matches_post_V9_postgres_columns() throws Exception {
    Set<String> pgCols = columnsFrom(pgDataSourceAfterAllPgMigrations());
    Set<String> h2Cols = columnsFrom(h2DataSourceAfterBaseline());

    assertThat(h2Cols)
        .as("H2 baseline must define the same columns as the post-V9 Postgres schema")
        .isEqualTo(pgCols);
  }

  private static Set<String> columnsFrom(DataSource ds) throws Exception {
    Set<String> out = new LinkedHashSet<>();
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT lower(table_name) AS t, lower(column_name) AS c, "
                    + "is_nullable, is_generated "
                    + "FROM information_schema.columns "
                    + "WHERE lower(table_name) IN ("
                    + TABLES.stream()
                        .map(t -> "'" + t + "'")
                        .reduce((a, b) -> a + "," + b)
                        .orElseThrow()
                    + ")")) {
      while (rs.next()) {
        out.add(
            rs.getString("t")
                + "."
                + rs.getString("c")
                + "|"
                + rs.getString("is_nullable")
                + "|"
                + normaliseGenerated(rs.getString("is_generated")));
      }
    }
    return out;
  }

  private static String normaliseGenerated(String v) {
    return v == null ? "NEVER" : v.toUpperCase();
  }

  private static DataSource pgDataSourceAfterAllPgMigrations() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration/postgresql", "classpath:db/migration/common")
        .load()
        .migrate();
    return ds;
  }

  private static DataSource h2DataSourceAfterBaseline() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL(
        "jdbc:h2:mem:sym_"
            + System.nanoTime()
            + ";DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
    ds.setUser("sa");
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration/h2", "classpath:db/migration/common")
        .load()
        .migrate();
    return ds;
  }
}
