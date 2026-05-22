package site.asm0dey.slidev.polls.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import site.asm0dey.slidev.polls.api.error.ProblemCode;

/**
 * Authenticates admin requests from the {@code SP_SESSION} cookie minted by {@code
 * AdminAuthController} (Phase 5) via {@link AdminSessionCookie}. Replaces the Spring servlet {@code
 * HttpSession}: this mechanism reads the encrypted cookie directly and builds a {@link
 * SecurityIdentity} with the {@code ADMIN} role, so no server-side session store is involved.
 *
 * <p>{@code authenticate} returns {@code nullItem()} (anonymous) when the cookie is absent or fails
 * {@link AdminSessionCookie#verify}; the {@code role-admin} HTTP permission policy then drives the
 * 401 challenge for guarded {@code /api/admin/*} paths, while public paths ({@code login}, {@code
 * setup}) — covered by the {@code admin-public} permit policy — stay reachable for anonymous
 * callers.
 *
 * <p>No {@code IdentityProvider} round-trip is needed (the cookie is self-contained), so {@link
 * #getCredentialTypes} is empty and the identity is built inline. The cookie verify is pure CPU
 * (AES-GCM, no I/O), so it is safe to run on the event loop.
 */
@ApplicationScoped
public class AdminSessionMechanism implements HttpAuthenticationMechanism {

  private final AdminSessionCookie cookie;
  private final ObjectMapper objectMapper;

  public AdminSessionMechanism(AdminSessionCookie cookie, ObjectMapper objectMapper) {
    this.cookie = cookie;
    this.objectMapper = objectMapper;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    Cookie sessionCookie = context.request().getCookie(AdminSessionCookie.COOKIE_NAME);
    if (sessionCookie == null) {
      return Uni.createFrom().nullItem();
    }
    Optional<String> username = cookie.verify(sessionCookie.getValue());
    if (username.isEmpty()) {
      return Uni.createFrom().nullItem();
    }
    SecurityIdentity identity =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(username.get()))
            .addRole(AdminPasswordIdentityProvider.ROLE_ADMIN)
            .build();
    return Uni.createFrom().item(identity);
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    // The body is written in sendChallenge; ChallengeData only conveys status + one header, so we
    // return a 401 marker with the JSON content-type to keep the contract consistent.
    return Uni.createFrom().item(new ChallengeData(401, "content-type", "application/json"));
  }

  @Override
  public Uni<Boolean> sendChallenge(RoutingContext context) {
    // Defer on deck paths so the DeckTokenMechanism owns their DECK_TOKEN_INVALID envelope; this
    // mechanism writes AUTH_REQUIRED for everything else. Path-scoping both mechanisms makes the
    // emitted code independent of Quarkus' (unstable) challenge-mechanism selection order.
    String path = context.normalizedPath();
    if (path != null && path.startsWith("/api/deck/")) {
      return Uni.createFrom().item(false);
    }
    return ProblemChallengeWriter.send(
        context, objectMapper, 401, ProblemCode.AUTH_REQUIRED, "authentication required");
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Collections.emptySet();
  }

  @Override
  public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
    return Uni.createFrom()
        .item(
            new HttpCredentialTransport(
                HttpCredentialTransport.Type.COOKIE, AdminSessionCookie.COOKIE_NAME));
  }
}
