package site.asm0dey.slidev.polls.api.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import org.junit.jupiter.api.Test;

/**
 * Cross-cutting coverage for the SPA catch-all routing, ported from the Spring {@code
 * SpaForwardingConfig} slice to the Vert.x {@link SpaRoutingFilter} over RestAssured. Static
 * resources are served from {@code META-INF/resources} ({@code /index.html} = voter shell, {@code
 * /admin/index.html} = backoffice shell), so the assertions key on each shell's distinguishing
 * asset marker rather than the test-stub {@code data-sp-shell} attribute that the old MockMvc slice
 * used.
 *
 * <ul>
 *   <li>@TS-043 — {@code GET /} and {@code GET /{slug}} serve the voter SPA shell as 200 text/html;
 *       {@code GET /api/polls/by-slug/{slug}} still returns a JSON Problem envelope, proving the
 *       catch-all does not swallow {@code /api/**}.
 *   <li>@TS-044 — {@code GET /admin/} and a dot-less deep link serve the backoffice shell; {@code
 *       GET /api/admin/polls} stays gated at 401 {@code AUTH_REQUIRED}.
 *   <li>@BUG-002 — {@code /admin/index.html} resolves to the literal file (no rewrite loop), a
 *       missing dotted asset returns a real 404, and {@code /admin} (no slash) 302-redirects to
 *       {@code /admin/}.
 * </ul>
 */
@QuarkusTest
class SpaCatchAllIT {

  // Distinguishing markers from the real META-INF/resources shells. The voter shell links
  // /assets/...; the backoffice shell links /admin/assets/... and titles itself "Backoffice".
  private static final String VOTER_MARKER = "/assets/index-";
  private static final String ADMIN_MARKER = "/admin/assets/index-";

  // Do not auto-follow the /admin → /admin/ redirect; the test asserts the 302 itself.
  private static final RestAssuredConfig NO_REDIRECT =
      RestAssuredConfig.config().redirect(RedirectConfig.redirectConfig().followRedirects(false));

  // @TS-043 — / serves the voter shell.
  @Test
  void root_serves_voter_shell() {
    given()
        .when()
        .get("/")
        .then()
        .statusCode(200)
        .contentType(containsString("text/html"))
        .body(containsString(VOTER_MARKER));
  }

  // @TS-043 — /{slug} (single kebab segment) serves the voter shell.
  @Test
  void slug_path_serves_voter_shell() {
    given()
        .when()
        .get("/some-talk")
        .then()
        .statusCode(200)
        .contentType(containsString("text/html"))
        .body(containsString(VOTER_MARKER));
  }

  // @TS-043 — the API route with the SAME slug still returns JSON (a Problem envelope), proving the
  // catch-all does not swallow /api/**.
  @Test
  void api_by_slug_is_not_swallowed_by_the_catch_all() {
    given()
        .when()
        .get("/api/polls/by-slug/some-talk")
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
  }

  // @TS-044 / @BUG-002 — /admin/ serves the backoffice shell directly from the static chain.
  @Test
  void admin_root_serves_backoffice_shell() {
    given()
        .when()
        .get("/admin/")
        .then()
        .statusCode(200)
        .contentType(containsString("text/html"))
        .body(containsString(ADMIN_MARKER));
  }

  // @TS-044 — /admin/polls is a client-side route; the filter reroutes the dot-less deep link to
  // the shell, not a stale 404.
  @Test
  void admin_sub_route_serves_backoffice_shell() {
    given()
        .when()
        .get("/admin/polls")
        .then()
        .statusCode(200)
        .contentType(containsString("text/html"))
        .body(containsString(ADMIN_MARKER));
  }

  // @BUG-002 — /admin/index.html resolves to the literal file (no rewrite loop).
  @Test
  void admin_index_html_serves_the_shell_file_without_looping() {
    given()
        .when()
        .get("/admin/index.html")
        .then()
        .statusCode(200)
        .body(containsString(ADMIN_MARKER));
  }

  // @BUG-002 — a missing dotted asset under /admin/assets/** returns a real 404, not the shell.
  @Test
  void admin_missing_asset_returns_404_not_the_shell() {
    given().when().get("/admin/assets/does-not-exist.js").then().statusCode(404);
  }

  // /admin without a trailing slash 302-redirects to /admin/.
  @Test
  void admin_without_trailing_slash_redirects_to_slashed_form() {
    given()
        .config(NO_REDIRECT)
        .when()
        .get("/admin")
        .then()
        .statusCode(302)
        .header("Location", equalTo("/admin/"));
  }

  // @BUG-002 — /admin/ is served as text/html so browsers render it as a document.
  @Test
  void admin_root_is_served_as_html() {
    given().when().get("/admin/").then().statusCode(200).contentType(containsString("text/html"));
  }

  // @TS-044 — the backoffice shell is served unauthenticated, but the data API stays gated:
  // /api/admin/polls must still surface 401 AUTH_REQUIRED.
  @Test
  void admin_api_remains_gated_behind_auth() {
    given()
        .when()
        .get("/api/admin/polls")
        .then()
        .statusCode(401)
        .body("code", equalTo("AUTH_REQUIRED"));
  }
}
