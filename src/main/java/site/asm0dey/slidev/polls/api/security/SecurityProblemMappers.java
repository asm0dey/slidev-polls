package site.asm0dey.slidev.polls.api.security;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.MDC;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.slidev.polls.api.error.Problem;
import site.asm0dey.slidev.polls.api.error.ProblemCode;

/**
 * Translates the security exceptions raised by the Quarkus authorization layer into the {@link
 * Problem} JSON envelope the rest of the API uses, replacing the Spring {@code
 * ProblemAuthenticationEntryPoint} / {@code ProblemAccessDeniedHandler} pairing.
 *
 * <p>These {@code @ServerExceptionMapper} methods run on the JAX-RS request path, so they reliably
 * own the body for failures on {@code quarkus-rest} resources — including the path-based split
 * between {@code AUTH_REQUIRED} and {@code DECK_TOKEN_INVALID} that the old entry point performed
 * from the request URI. The mechanisms' {@code sendChallenge} remains the fallback for failures
 * that never reach a JAX-RS resource.
 *
 * <p>The {@code correlationId} is read from the JBoss-logging {@code MDC} (Phase 5 wires the filter
 * that populates it); a missing key yields {@code null}, which the envelope omits.
 */
public class SecurityProblemMappers {

  private static final String DECK_PATH_PREFIX = "/api/deck/";
  private static final String MDC_CORRELATION_KEY = "correlationId";

  @ServerExceptionMapper
  public RestResponse<Problem> mapUnauthorized(
      UnauthorizedException ex, RoutingContext routingContext) {
    return authRequired(routingContext);
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapAuthenticationFailed(
      AuthenticationFailedException ex, RoutingContext routingContext) {
    return authRequired(routingContext);
  }

  @ServerExceptionMapper
  public RestResponse<Problem> mapForbidden(ForbiddenException ex) {
    Problem body = new Problem(ProblemCode.FORBIDDEN, "access denied", correlationId());
    return RestResponse.ResponseBuilder.create(Response.Status.FORBIDDEN, body)
        .type(MediaType.APPLICATION_JSON_TYPE)
        .build();
  }

  private RestResponse<Problem> authRequired(RoutingContext routingContext) {
    String path = routingContext == null ? null : routingContext.normalizedPath();
    boolean deck = path != null && path.startsWith(DECK_PATH_PREFIX);
    ProblemCode code = deck ? ProblemCode.DECK_TOKEN_INVALID : ProblemCode.AUTH_REQUIRED;
    String message = deck ? "deck token missing or revoked" : "authentication required";
    Problem body = new Problem(code, message, correlationId());
    return RestResponse.ResponseBuilder.create(Response.Status.UNAUTHORIZED, body)
        .type(MediaType.APPLICATION_JSON_TYPE)
        .build();
  }

  private static String correlationId() {
    Object value = MDC.get(MDC_CORRELATION_KEY);
    return value == null ? null : value.toString();
  }
}
