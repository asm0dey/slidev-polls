package site.asm0dey.slidev.polls.api.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.error.NotOwnerException;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Security-slice coverage for the admin surface, ported to {@code @QuarkusTest} + RestAssured.
 *
 * <ul>
 *   <li>@TS-001 — every {@code /api/admin/**} endpoint returns 401 {@code AUTH_REQUIRED} with no
 *       session (the Quarkus {@code role-admin} HTTP-auth policy denies before JAX-RS dispatch).
 *   <li>@TS-040 — an authenticated non-owner GET on another presenter's poll → 403 {@code
 *       FORBIDDEN} ({@code NotOwnerException} from the service, mapped by {@code
 *       DomainExceptionMappers}).
 *   <li>@TS-041 — the same for a mutation (DELETE / PATCH), which must also clear the CSRF
 *       double-submit before the ownership check fires.
 * </ul>
 *
 * Authentication is simulated with {@link TestSecurity}; the downstream {@link PollService} is an
 * {@link InjectMock}. State-changing requests carry a matching {@code XSRF-TOKEN} cookie + {@code
 * X-XSRF-TOKEN} header so {@code AdminCsrfFilter} lets them through to the controller.
 */
@QuarkusTest
class AdminAuthWebMvcTest {

  private static final String CSRF = "csrf-token-value";

  @InjectMock PollService pollService;

  // ---- @TS-001: unauthenticated → 401 AUTH_REQUIRED -----------------------

  @Test
  void unauthenticated_list_returns_401_auth_required() {
    given()
        .when()
        .get("/api/admin/polls")
        .then()
        .statusCode(401)
        .body("code", equalTo("AUTH_REQUIRED"))
        .body("message", not(emptyString()));
  }

  @Test
  void unauthenticated_get_by_id_returns_401() {
    given()
        .when()
        .get("/api/admin/polls/" + UUID.randomUUID())
        .then()
        .statusCode(401)
        .body("code", equalTo("AUTH_REQUIRED"));
  }

  @Test
  void unauthenticated_delete_returns_401() {
    given()
        .when()
        .delete("/api/admin/polls/" + UUID.randomUUID())
        .then()
        .statusCode(401)
        .body("code", equalTo("AUTH_REQUIRED"));
  }

  @Test
  void unauthenticated_qr_returns_401() {
    given()
        .when()
        .get("/api/admin/polls/" + UUID.randomUUID() + "/qr.png")
        .then()
        .statusCode(401)
        .body("code", equalTo("AUTH_REQUIRED"));
  }

  // ---- @TS-040 / @TS-041: authenticated non-owner → 403 FORBIDDEN ----------

  @Test
  @TestSecurity(user = "bob", roles = "ADMIN")
  void foreign_presenter_get_returns_403() {
    UUID pollId = UUID.randomUUID();
    when(pollService.getForOwner(eq(pollId), eq("bob")))
        .thenThrow(new NotOwnerException("poll " + pollId + " is not owned by bob"));

    given()
        .when()
        .get("/api/admin/polls/" + pollId)
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
  }

  @Test
  @TestSecurity(user = "bob", roles = "ADMIN")
  void foreign_presenter_delete_returns_403() {
    UUID pollId = UUID.randomUUID();
    doThrow(new NotOwnerException("not yours"))
        .when(pollService)
        .deleteForOwner(eq(pollId), eq("bob"));

    given()
        .cookie("XSRF-TOKEN", CSRF)
        .header("X-XSRF-TOKEN", CSRF)
        .when()
        .delete("/api/admin/polls/" + pollId)
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
  }

  @Test
  @TestSecurity(user = "bob", roles = "ADMIN")
  void foreign_presenter_patch_returns_403() {
    UUID pollId = UUID.randomUUID();
    when(pollService.updateForOwner(eq(pollId), eq("bob"), any()))
        .thenThrow(new NotOwnerException("not yours"));

    given()
        .cookie("XSRF-TOKEN", CSRF)
        .header("X-XSRF-TOKEN", CSRF)
        .contentType(ContentType.JSON)
        .body("{\"title\":\"Renamed\"}")
        .when()
        .patch("/api/admin/polls/" + pollId)
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
  }

  @Test
  @TestSecurity(user = "alice", roles = "ADMIN")
  void rightful_owner_can_read_their_own_poll() {
    UUID pollId = UUID.randomUUID();
    when(pollService.getForOwner(eq(pollId), eq("alice"))).thenReturn(fixturePoll(pollId, "alice"));

    given()
        .when()
        .get("/api/admin/polls/" + pollId)
        .then()
        .statusCode(200)
        .body("slug", equalTo("my-talk"));
  }

  // @TS-001 — login stays reachable without a session; an empty body hits bean-validation, not the
  // auth gate, so we assert VALIDATION_FAILED rather than AUTH_REQUIRED.
  @Test
  void login_endpoint_is_reachable_without_session() {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/api/admin/login")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_FAILED"));
  }

  private static Poll fixturePoll(UUID pollId, String owner) {
    return new Poll(
        pollId,
        owner,
        "My Talk",
        "my-talk",
        PollStatus.DRAFT,
        null,
        List.of(),
        List.of(),
        Instant.now(),
        Instant.now());
  }
}
