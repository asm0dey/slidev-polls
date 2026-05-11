package site.asm0dey.slidev.polls.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Presenter sign-in / sign-out. {@code POST /api/admin/login} takes a JSON body, runs it through
 * the configured {@link AuthenticationManager} (the {@code DaoAuthenticationProvider} in {@link
 * site.asm0dey.slidev.polls.api.security.SecurityConfig}) and — on success — publishes the
 * resulting {@link Authentication} to a persisted {@code SecurityContext} so the next request from
 * the same session cookie is authenticated.
 *
 * <p>Errors come out of the {@code GlobalExceptionHandler} as {@code Problem{AUTH_REQUIRED}} 401s
 * via the {@code AuthenticationException} handler chain, keeping the envelope consistent with every
 * other 401 on the API (@TS-001).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  public AdminAuthController(AuthenticationManager authenticationManager) {
    this.authenticationManager = authenticationManager;
  }

  @PostMapping("/login")
  public ResponseEntity<Void> login(
      @Valid @RequestBody LoginRequest body,
      HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    UsernamePasswordAuthenticationToken token =
        UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password());
    try {
      Authentication authenticated = authenticationManager.authenticate(token);
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(authenticated);
      SecurityContextHolder.setContext(context);
      // Writing the context to the repository forces a session on first login and stores the
      // authentication against JSESSIONID/SP_SESSION; subsequent requests are reloaded
      // automatically by SecurityContextPersistenceFilter.
      securityContextRepository.saveContext(context, request, response);
    } catch (BadCredentialsException ex) {
      // Rewrap so the GlobalExceptionHandler's AuthenticationException branch fires. The default
      // BadCredentialsException message leaks internal wording ("Bad credentials"); we keep the
      // user-facing message on the wrapped exception.
      throw new BadCredentialsAsAuthException("invalid username or password", ex);
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
    // Clear the SecurityContext (in-memory) and the stored context (session).
    SecurityContextHolder.clearContext();
    SecurityContext empty = SecurityContextHolder.createEmptyContext();
    securityContextRepository.saveContext(empty, request, response);
    var session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    return ResponseEntity.noContent().build();
  }

  /** Mirrors {@code LoginRequest} in {@code openapi.yaml}. */
  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  /**
   * Carries the original {@link BadCredentialsException} as the cause so the global handler's
   * {@code AuthenticationException} branch fires uniformly, without leaking Spring Security's
   * internal "Bad credentials" wording.
   */
  static final class BadCredentialsAsAuthException extends AuthenticationException {
    BadCredentialsAsAuthException(String msg, Throwable cause) {
      super(msg, cause);
    }
  }
}
