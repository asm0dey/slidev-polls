package site.asm0dey.slidev.polls.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import site.asm0dey.slidev.polls.core.domain.DeckToken;
import site.asm0dey.slidev.polls.core.service.DeckTokenService;

/**
 * Authenticates requests on {@code /api/deck/**} by matching the bearer in the {@code X-Deck-Token}
 * header against an unrevoked row in {@code deck_tokens}. On success the filter attaches a {@link
 * DeckPrincipal} carrying {@code pollId} so the activation controller can enforce the poll-scope
 * invariant (@TS-054).
 *
 * <p>The filter runs only on {@code /api/deck/**} — {@code SecurityConfig} does not register it
 * anywhere else, so the token cannot be re-used on the backoffice (@TS-057). An absent or unknown
 * bearer leaves the context unauthenticated; the {@code authorizeHttpRequests} rule on {@code
 * /api/deck/**} then forces the {@code ProblemAuthenticationEntryPoint} to emit {@code
 * DECK_TOKEN_INVALID}.
 */
@Component
public class DeckTokenAuthenticationFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Deck-Token";

  private final DeckTokenService service;

  public DeckTokenAuthenticationFilter(DeckTokenService service) {
    this.service = service;
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HEADER);
    if (header != null && !header.isBlank()) {
      Optional<DeckToken> match = service.resolveLive(header);
      if (match.isPresent()) {
        DeckToken token = match.get();
        DeckPrincipal principal = new DeckPrincipal(token.id(), token.pollId(), token.label());
        SecurityContextHolder.getContext()
            .setAuthentication(new DeckAuthenticationToken(principal));
      }
    }
    try {
      chain.doFilter(request, response);
    } finally {
      // Deck auth is request-scoped; the token MUST NOT bleed across threads.
      SecurityContextHolder.clearContext();
    }
  }

  @Override
  protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
    String path = request.getRequestURI();
    return path == null || !path.startsWith("/api/deck/");
  }

  /** Authentication wrapper around {@link DeckPrincipal}. Always authenticated. */
  public static final class DeckAuthenticationToken extends AbstractAuthenticationToken {

    private final DeckPrincipal principal;

    public DeckAuthenticationToken(DeckPrincipal principal) {
      super(principal.authorities());
      this.principal = principal;
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public DeckPrincipal getPrincipal() {
      return principal;
    }

    @Override
    public boolean equals(Object o) {
      if (getClass() != o.getClass()) return false;
      if (!super.equals(o)) return false;
      DeckAuthenticationToken that = (DeckAuthenticationToken) o;
      return Objects.equals(principal, that.principal);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), principal);
    }
  }
}
