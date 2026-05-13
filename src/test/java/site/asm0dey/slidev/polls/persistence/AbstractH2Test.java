package site.asm0dey.slidev.polls.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;

public abstract class AbstractH2Test {

  protected static DataSource freshH2() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL(
        "jdbc:h2:mem:polls_"
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

  protected static DSLContext dsl(DataSource ds) {
    return DSL.using(new DefaultConfiguration().set(ds).set(SQLDialect.H2));
  }
}
