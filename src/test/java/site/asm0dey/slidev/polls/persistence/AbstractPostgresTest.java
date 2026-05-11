package site.asm0dey.slidev.polls.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for every {@code poll-persistence} integration test that needs a real Postgres. A
 * single PostgreSQL 16 container is started on class load and reused by every subclass for the rest
 * of the JVM's lifetime — starting a fresh container per test class would dominate wall- clock;
 * {@code @BeforeAll} wipes and re-applies Flyway instead so each class still begins from a clean,
 * fully-migrated schema.
 *
 * <p>Subclasses get a ready-to-use {@link DSLContext} pointing at the same container, keeping the
 * test's query surface identical to production. The class is deliberately not Spring-aware: {@code
 * poll-core} and {@code poll-persistence} keep Spring Boot out of their test classpaths (Principle
 * V), and the repositories under test in later tasks only need a JDBC {@link DataSource} plus jOOQ.
 */
public abstract class AbstractPostgresTest {

  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .cleanDisabled(false)
        .load()
        .migrate();
  }

  protected static DataSource dataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }

  protected static DSLContext dsl() {
    return DSL.using(new DefaultConfiguration().set(dataSource()).set(SQLDialect.POSTGRES));
  }
}
