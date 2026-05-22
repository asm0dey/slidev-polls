package site.asm0dey.slidev.polls.config;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

@ApplicationScoped
public class JooqConfig {
  @Inject
  @DataSource("postgres")
  AgroalDataSource postgres;

  @Inject
  @DataSource("h2")
  AgroalDataSource h2;

  @Inject
  @ConfigProperty(name = "app.database.vendor")
  String vendor;

  @Produces
  @ApplicationScoped
  public DSLContext dslContext() {
    // DataSourceConnectionProvider over the JTA-enabled active Agroal datasource so jOOQ
    // enlists in the ambient Narayana transaction (D2). Dialect matches the active vendor.
    boolean isH2 = "h2".equalsIgnoreCase(vendor);
    return DSL.using(isH2 ? h2 : postgres, isH2 ? SQLDialect.H2 : SQLDialect.POSTGRES);
  }
}
