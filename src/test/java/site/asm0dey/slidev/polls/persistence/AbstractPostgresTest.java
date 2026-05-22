package site.asm0dey.slidev.polls.persistence;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.jooq.DSLContext;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Base class for persistence integration tests that need a real Postgres. Under Quarkus the
 * throwaway Postgres is supplied by Dev Services (the {@code postgres} datasource has no JDBC URL
 * in the {@code %test} profile), and Flyway has already migrated the active vendor on startup via
 * {@code FlywayMigrator}. Subclasses get the application's {@code @Default} {@link DSLContext} —
 * the very one production code uses — pointed at that container.
 *
 * <p>Replaces the prior Testcontainers/Spring boot: no manual container lifecycle, no manual
 * Flyway, no Spring. The schema is created once per JVM by the app's startup migration; tests
 * insert unique rows (UUID-suffixed slugs / owner usernames) so they do not collide across classes.
 */
@QuarkusTest
public abstract class AbstractPostgresTest {

  @Inject DSLContext injectedDsl;

  @Inject
  @DataSource("postgres")
  AgroalDataSource pgDataSource;

  /**
   * The application's active-vendor {@link DSLContext} (Dev Services Postgres under {@code %test}).
   */
  protected DSLContext pgDsl() {
    return injectedDsl;
  }

  /**
   * Provisions a brand-new, empty database on the same Dev Services Postgres server and returns a
   * {@link javax.sql.DataSource} pointed at it. Migration ITs need a pristine schema they can run
   * Flyway against at a controlled {@code target} version — the application-managed
   * {@code @Default} datasource has already been migrated to HEAD on startup, so it cannot be
   * reused for that.
   *
   * <p>The server host/port come from a live connection's URL metadata; Dev Services uses {@code
   * quarkus}/{@code quarkus} for user/password/dbname, and CREATE DATABASE on that base DB succeeds
   * because the Dev Services role is a superuser.
   */
  protected PGSimpleDataSource freshPgDatabase() {
    String baseUrl;
    String user;
    try (Connection c = pgDataSource.getConnection()) {
      baseUrl = c.getMetaData().getURL();
      user = c.getMetaData().getUserName();
    } catch (SQLException e) {
      throw new RuntimeException("could not read Dev Services Postgres connection metadata", e);
    }
    String dbName = "mig_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = pgDataSource.getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE " + dbName);
    } catch (SQLException e) {
      throw new RuntimeException("could not create throwaway database " + dbName, e);
    }
    // baseUrl is jdbc:postgresql://host:port/quarkus[?params]; swap the database segment.
    String prefix = baseUrl.substring(0, baseUrl.indexOf("://") + 3);
    String rest = baseUrl.substring(prefix.length());
    int slash = rest.indexOf('/');
    String authority = slash >= 0 ? rest.substring(0, slash) : rest;
    String tail = slash >= 0 ? rest.substring(slash + 1) : "";
    int q = tail.indexOf('?');
    String params = q >= 0 ? tail.substring(q) : "";
    String freshUrl = prefix + authority + "/" + dbName + params;

    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(freshUrl);
    ds.setUser(user);
    ds.setPassword(user); // Dev Services: username == password == dbname == "quarkus"
    return ds;
  }
}
