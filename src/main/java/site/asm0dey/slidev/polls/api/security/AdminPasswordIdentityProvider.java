package site.asm0dey.slidev.polls.api.security;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import site.asm0dey.slidev.polls.core.service.AdminUserRepository;

/**
 * Verifies presenter username/password credentials against the {@code admin_user} table, replacing
 * the Spring {@code AdminUserDetailsService} + {@code DaoAuthenticationProvider} pairing.
 *
 * <p>The Argon2id hash stored per-install is compared with {@link Argon2PasswordHasher} (the same
 * algorithm and PHC layout the old Spring encoder used), so existing rows validate unchanged.
 * Usernames are persisted lowercase (a CHECK constraint enforces it), so the incoming login is
 * lowercased to keep the form case-insensitive — matching the prior {@code
 * AdminUserDetailsService}.
 *
 * <p>On success the identity carries the username as principal plus the single {@code ADMIN} role;
 * ownership of polls is enforced downstream by the authenticated principal's name, not by roles.
 *
 * <p>The repository lookup and Argon2 verification are both blocking, so the work is delegated to
 * {@link AuthenticationRequestContext#runBlocking} rather than executed on the event loop. A
 * missing user or a hash mismatch fails the {@link Uni} with {@link AuthenticationFailedException}.
 */
@ApplicationScoped
public class AdminPasswordIdentityProvider
    implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

  static final String ROLE_ADMIN = "ADMIN";

  private final AdminUserRepository repository;
  private final Argon2PasswordHasher hasher;

  @Inject
  public AdminPasswordIdentityProvider(
      AdminUserRepository repository, Argon2PasswordHasher hasher) {
    this.repository = repository;
    this.hasher = hasher;
  }

  @Override
  public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
    return UsernamePasswordAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      UsernamePasswordAuthenticationRequest request, AuthenticationRequestContext context) {
    return context.runBlocking(() -> verify(request));
  }

  private SecurityIdentity verify(UsernamePasswordAuthenticationRequest request) {
    String username = request.getUsername();
    if (username == null || username.isBlank()) {
      throw new AuthenticationFailedException();
    }
    String lookup = username.toLowerCase();

    Optional<String> storedHash = repository.findPasswordHash(lookup);
    if (storedHash.isEmpty()) {
      throw new AuthenticationFailedException();
    }

    String raw = new String(request.getPassword().getPassword());
    if (!hasher.matches(raw, storedHash.get())) {
      throw new AuthenticationFailedException();
    }

    return QuarkusSecurityIdentity.builder()
        .setPrincipal(new QuarkusPrincipal(lookup))
        .addRole(ROLE_ADMIN)
        .build();
  }
}
