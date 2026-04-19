package site.asm0dey.slidev.polls.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import site.asm0dey.slidev.polls.api.error.Problem;
import site.asm0dey.slidev.polls.api.error.ProblemCode;
import site.asm0dey.slidev.polls.api.logging.CorrelationIdFilter;

/**
 * Translates Spring Security authentication failures on guarded endpoints (no session / expired
 * session) into a {@link Problem} JSON body with code {@code AUTH_REQUIRED} — the rest of the API
 * uses the same shape, so callers don't have to special-case Spring Security's default behaviour.
 *
 * <p>Runs on the exception-translation path, which sits behind {@link CorrelationIdFilter}; the
 * correlation id on the MDC is still populated when we build the body, so logs and the 401 response
 * share a lookup key (Principle VI, @TS-001).
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  public ProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
      throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    Problem body =
        new Problem(
            ProblemCode.AUTH_REQUIRED,
            "authentication required",
            MDC.get(CorrelationIdFilter.MDC_KEY));
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
