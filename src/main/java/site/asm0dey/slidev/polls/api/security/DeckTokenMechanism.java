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
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import site.asm0dey.slidev.polls.api.error.ProblemCode;
import site.asm0dey.slidev.polls.core.domain.DeckToken;
import site.asm0dey.slidev.polls.core.service.DeckTokenService;

/**
 * Authenticates {@code /api/deck/*} requests by matching the bearer in the {@code X-Deck-Token}
 * header against an unrevoked {@code deck_tokens} row. Replaces the Spring {@code
 * DeckTokenAuthenticationFilter}: on a match it builds a {@link SecurityIdentity} with the {@code
 * DECK} role and attaches the {@link DeckPrincipal} (carrying {@code pollId}) as an attribute so
 * the activation resource can enforce the poll-scope invariant (@TS-054).
 *
 * <p>The mechanism only acts on {@code /api/deck/} paths; on any other path it returns {@code
 * nullItem()} so the admin session mechanism and the permission policies decide. An absent or
 * unknown bearer also yields {@code nullItem()}; the {@code role-deck} permission policy then
 * drives the 401 {@code DECK_TOKEN_INVALID} challenge, while {@code /api/deck/auth/login} stays
 * reachable via the {@code deck-public} permit policy.
 *
 * <p>{@code DeckTokenService.resolveLive} performs a blocking JDBC lookup, so it MUST NOT run on
 * the event loop. The lookup is wrapped in a {@code Uni} that subscribes on the default Mutiny
 * worker pool ({@code runSubscriptionOn(Infrastructure.getDefaultWorkerPool())}); the identity is
 * then assembled on the resulting worker thread.
 */
@ApplicationScoped
public class DeckTokenMechanism implements HttpAuthenticationMechanism {

  static final String HEADER = "X-Deck-Token";
  private static final String DECK_PATH_PREFIX = "/api/deck/";

  private final DeckTokenService service;
  private final ObjectMapper objectMapper;

  public DeckTokenMechanism(DeckTokenService service, ObjectMapper objectMapper) {
    this.service = service;
    this.objectMapper = objectMapper;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String path = context.normalizedPath();
    if (path == null || !path.startsWith(DECK_PATH_PREFIX)) {
      return Uni.createFrom().nullItem();
    }
    String header = context.request().getHeader(HEADER);
    if (header == null || header.isBlank()) {
      return Uni.createFrom().nullItem();
    }
    return Uni.createFrom()
        .item(() -> service.resolveLive(header))
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
        .map(this::toIdentity);
  }

  private SecurityIdentity toIdentity(Optional<DeckToken> match) {
    if (match.isEmpty()) {
      return null;
    }
    DeckToken token = match.get();
    DeckPrincipal principal = new DeckPrincipal(token.id(), token.pollId(), token.label());
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(new QuarkusPrincipal("deck:" + token.id()))
        .addRole(DeckPrincipal.ROLE)
        .addAttribute(DeckPrincipal.ATTRIBUTE, principal)
        .build();
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    return Uni.createFrom().item(new ChallengeData(401, "content-type", "application/json"));
  }

  @Override
  public Uni<Boolean> sendChallenge(RoutingContext context) {
    // Only own the challenge for deck paths. Quarkus may pick any registered mechanism to send the
    // 401 for an unauthenticated request; if this one were to fire on an /api/admin/** request it
    // would mislabel the envelope DECK_TOKEN_INVALID. Defer (return false) on non-deck paths so the
    // AdminSessionMechanism writes AUTH_REQUIRED instead. The selection order is otherwise unstable
    // across runs, which surfaced as a flaky AUTH_REQUIRED-vs-DECK_TOKEN_INVALID assertion.
    String path = context.normalizedPath();
    if (path == null || !path.startsWith(DECK_PATH_PREFIX)) {
      return Uni.createFrom().item(false);
    }
    return ProblemChallengeWriter.send(
        context,
        objectMapper,
        401,
        ProblemCode.DECK_TOKEN_INVALID,
        "deck token missing or revoked");
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Collections.emptySet();
  }

  @Override
  public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
    return Uni.createFrom()
        .item(new HttpCredentialTransport(HttpCredentialTransport.Type.OTHER_HEADER, HEADER));
  }
}
