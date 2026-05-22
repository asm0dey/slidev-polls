package site.asm0dey.slidev.polls.persistence;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jooq.DSLContext;

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

  /**
   * The application's active-vendor {@link DSLContext} (Dev Services Postgres under {@code %test}).
   */
  protected DSLContext pgDsl() {
    return injectedDsl;
  }
}
