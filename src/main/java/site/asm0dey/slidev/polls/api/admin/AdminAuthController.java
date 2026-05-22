package site.asm0dey.slidev.polls.api.admin;

import io.quarkus.security.credential.PasswordCredential;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import java.time.Duration;
import java.util.UUID;
import org.jboss.resteasy.reactive.RestResponse;
import site.asm0dey.slidev.polls.api.security.AdminSessionCookie;

/**
 * Presenter sign-in / sign-out. {@code POST /api/admin/login} authenticates the supplied
 * credentials through Quarkus' {@link IdentityProviderManager} (the {@code
 * AdminPasswordIdentityProvider}) and — on success — mints the encrypted {@code SP_SESSION} cookie
 * via {@link AdminSessionCookie} so the next request from the same browser is authenticated by
 * {@code AdminSessionMechanism}. No server-side session store is involved (the cookie is
 * self-contained).
 *
 * <p>A readable {@code XSRF-TOKEN} cookie is set on login so the SPA can echo it back as {@code
 * X-XSRF-TOKEN} for the {@code AdminCsrfFilter}.
 *
 * <p>Bad credentials fail the authentication {@link io.smallrye.mutiny.Uni} with an {@code
 * AuthenticationFailedException}, which {@code SecurityProblemMappers} maps to a 401 {@code
 * AUTH_REQUIRED} without leaking internal wording (@TS-001).
 */
@Path("/api/admin")
@ApplicationScoped
@RunOnVirtualThread
public class AdminAuthController {

  /** Absolute lifetime of an admin session; matches the cookie Max-Age. */
  private static final Duration SESSION_TTL = Duration.ofHours(12);

  /**
   * Readable double-submit CSRF cookie name. Mirrors the {@code AdminCsrfFilter} expectation; the
   * SPA reads it (HttpOnly=false) and echoes it as the {@code X-XSRF-TOKEN} header.
   */
  private static final String XSRF_COOKIE_NAME = "XSRF-TOKEN";

  private final IdentityProviderManager idm;
  private final AdminSessionCookie sessionCookie;

  public AdminAuthController(IdentityProviderManager idm, AdminSessionCookie sessionCookie) {
    this.idm = idm;
    this.sessionCookie = sessionCookie;
  }

  @POST
  @Path("/login")
  public RestResponse<Void> login(@Valid LoginRequest body, @Context RoutingContext ctx) {
    // Blocking await is safe here: the method runs on a virtual thread. A failed credential fails
    // the Uni with AuthenticationFailedException, which propagates to SecurityProblemMappers → 401.
    SecurityIdentity identity =
        idm.authenticate(
                new UsernamePasswordAuthenticationRequest(
                    body.username(), new PasswordCredential(body.password().toCharArray())))
            .await()
            .indefinitely();

    boolean secure = ctx.request().isSSL();
    String username = identity.getPrincipal().getName();
    String sessionValue = sessionCookie.mint(username, SESSION_TTL);
    String sessionHeader = sessionCookie.setCookieHeader(sessionValue, SESSION_TTL, secure);
    String xsrfHeader = buildXsrfCookie(UUID.randomUUID().toString(), SESSION_TTL, secure);

    return RestResponse.ResponseBuilder.create(RestResponse.Status.NO_CONTENT, (Void) null)
        .header(HttpHeaders.SET_COOKIE, sessionHeader)
        .header(HttpHeaders.SET_COOKIE, xsrfHeader)
        .build();
  }

  @POST
  @Path("/logout")
  public RestResponse<Void> logout(@Context RoutingContext ctx) {
    boolean secure = ctx.request().isSSL();
    // No server session to invalidate — clearing the cookies is sufficient.
    return RestResponse.ResponseBuilder.create(RestResponse.Status.NO_CONTENT, (Void) null)
        .header(HttpHeaders.SET_COOKIE, sessionCookie.clearCookieHeader(secure))
        .header(HttpHeaders.SET_COOKIE, clearXsrfCookie(secure))
        .build();
  }

  private static String buildXsrfCookie(String value, Duration ttl, boolean secure) {
    // Readable by JS (no HttpOnly) so the SPA can echo it as X-XSRF-TOKEN; SameSite=Lax, Path=/.
    StringBuilder sb = new StringBuilder();
    sb.append(XSRF_COOKIE_NAME).append('=').append(value);
    sb.append("; Path=/");
    sb.append("; SameSite=Lax");
    sb.append("; Max-Age=").append(ttl.toSeconds());
    if (secure) {
      sb.append("; Secure");
    }
    return sb.toString();
  }

  private static String clearXsrfCookie(boolean secure) {
    StringBuilder sb = new StringBuilder();
    sb.append(XSRF_COOKIE_NAME).append('=');
    sb.append("; Path=/");
    sb.append("; SameSite=Lax");
    sb.append("; Max-Age=0");
    if (secure) {
      sb.append("; Secure");
    }
    return sb.toString();
  }

  /** Mirrors {@code LoginRequest} in {@code openapi.yaml}. */
  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
}
