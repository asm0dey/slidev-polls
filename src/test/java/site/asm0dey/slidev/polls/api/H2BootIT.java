package site.asm0dey.slidev.polls.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke for the H2 path. Boots the whole Quarkus app with {@code app.database.vendor=h2}
 * so {@code FlywayMigrator} migrates the H2 baseline and {@code JooqConfig} binds the H2 datasource
 * + dialect, then hits one HTTP endpoint that exercises the repository's multiset emulation and
 * generated-column reads.
 *
 * <p>A 404 with the standard Problem envelope ({@code code = "NOT_FOUND"}) proves the SQL parses
 * and executes on H2: {@code {vendor}} substitution wired Flyway to the H2 baseline, jOOQ inferred
 * the H2 dialect, and the generated-column read renders correctly. A 500 would flag a multiset or
 * generated-column regression. A green {@code H2MigrationIT} alone does not prove that.
 */
@QuarkusTest
@TestProfile(H2BootIT.H2VendorProfile.class)
class H2BootIT {

  public static class H2VendorProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Flip the active vendor to H2 so FlywayMigrator migrates the H2 baseline and JooqConfig
      // binds the H2 datasource + dialect. Both Flyway beans (postgres + h2) are injected at
      // startup regardless of the active vendor, so the postgres datasource must stay configured
      // — leave the default %test Postgres Dev Services in place; only the *active* vendor flips.
      return Map.of("app.database.vendor", "h2");
    }
  }

  // Hits PublicPollController.getBySlug → PollRepositoryImpl.findBySlug, which exercises the
  // SLUG_LOWER generated column plus the SELECT shape used to materialise a Poll.
  @Test
  void public_poll_endpoint_returns_not_found_for_unknown_slug_on_h2() {
    given()
        .when()
        .get("/api/polls/by-slug/no-such-slug")
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
  }
}
