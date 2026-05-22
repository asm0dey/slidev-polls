package site.asm0dey.slidev.polls.config;

import io.quarkus.flyway.FlywayDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;

@ApplicationScoped
public class FlywayMigrator {
  @Inject
  @FlywayDataSource("postgres")
  Flyway postgres;

  @Inject
  @FlywayDataSource("h2")
  Flyway h2;

  @Inject
  @ConfigProperty(name = "app.database.vendor")
  String vendor;

  void onStart(@Observes StartupEvent ev) {
    ("h2".equalsIgnoreCase(vendor) ? h2 : postgres).migrate();
  }
}
