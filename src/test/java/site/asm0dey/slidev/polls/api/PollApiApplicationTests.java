package site.asm0dey.slidev.polls.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

/**
 * Quarkus replacement for the Spring {@code contextLoads()} smoke. Asserts the application boots on
 * the default (Postgres Dev Services) test profile and that the core jOOQ {@link DSLContext} bean
 * is wired — a minimal proof the CDI container and datasource came up.
 */
@QuarkusTest
class PollApiApplicationTests {

  @Inject DSLContext dsl;

  @Test
  void contextLoads() {
    assertThat(dsl).as("DSLContext is injectable after boot").isNotNull();
  }
}
