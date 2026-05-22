package site.asm0dey.slidev.polls.api.security;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Set;
import org.jboss.logging.MDC;
import site.asm0dey.slidev.polls.api.error.Problem;
import site.asm0dey.slidev.polls.api.error.ProblemCode;

/**
 * Double-submit CSRF guard for the backoffice JSON flow, replacing the Spring {@code
 * CookieCsrfTokenRepository} wiring. The {@code quarkus-rest-csrf} extension is form/cookie
 * oriented and applies one global policy keyed on {@code form-read}; it cannot scope enforcement to
 * {@code /api/admin/**} while exempting {@code login}/{@code setup} for a header-based JSON SPA, so
 * the guard is implemented explicitly here.
 *
 * <p>On a state-changing method ({@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE}) under
 * {@code /api/admin/} — excluding {@code login}, {@code setup}, and {@code setup/**}, which the
 * caller hits before any session exists — the {@code X-XSRF-TOKEN} header must equal the {@code
 * XSRF-TOKEN} cookie (the SPA reads the non-HttpOnly cookie and echoes it). A missing or mismatched
 * token aborts with a {@link Problem} {@code FORBIDDEN} 403, matching the prior Spring {@code
 * ProblemAccessDeniedHandler} envelope.
 *
 * <p>Runs at {@code AUTHENTICATION - 10} so it precedes resource dispatch; correlationId is read
 * from the JBoss-logging MDC (Phase 5 wires the populating filter; a missing key is omitted).
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 10)
public class AdminCsrfFilter implements ContainerRequestFilter {

  static final String COOKIE_NAME = "XSRF-TOKEN";
  static final String HEADER_NAME = "X-XSRF-TOKEN";
  private static final String ADMIN_PREFIX = "api/admin/";
  private static final String MDC_CORRELATION_KEY = "correlationId";
  private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");
  // Paths (relative to /api/admin/) reachable before a session exists, so there is no CSRF surface.
  private static final Set<String> EXEMPT_SUFFIXES = Set.of("login", "setup");
  private static final String SETUP_SUBPATH_PREFIX = "setup/";

  @Override
  public void filter(ContainerRequestContext ctx) {
    if (!STATE_CHANGING.contains(ctx.getMethod())) {
      return;
    }
    // getPath() is relative to the application root with no leading slash.
    String path = ctx.getUriInfo().getPath();
    if (path == null || !path.startsWith(ADMIN_PREFIX)) {
      return;
    }
    String suffix = path.substring(ADMIN_PREFIX.length());
    if (EXEMPT_SUFFIXES.contains(suffix) || suffix.startsWith(SETUP_SUBPATH_PREFIX)) {
      return;
    }

    String headerToken = ctx.getHeaderString(HEADER_NAME);
    Cookie cookie = ctx.getCookies().get(COOKIE_NAME);
    String cookieToken = cookie == null ? null : cookie.getValue();

    if (headerToken == null
        || headerToken.isBlank()
        || cookieToken == null
        || cookieToken.isBlank()
        || !constantTimeEquals(headerToken, cookieToken)) {
      ctx.abortWith(forbidden());
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    int diff = 0;
    for (int i = 0; i < a.length(); i++) {
      diff |= a.charAt(i) ^ b.charAt(i);
    }
    return diff == 0;
  }

  private static Response forbidden() {
    Object correlationId = MDC.get(MDC_CORRELATION_KEY);
    Problem body =
        new Problem(
            ProblemCode.FORBIDDEN,
            "CSRF token missing or invalid",
            correlationId == null ? null : correlationId.toString());
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON_TYPE)
        .entity(body)
        .build();
  }
}
