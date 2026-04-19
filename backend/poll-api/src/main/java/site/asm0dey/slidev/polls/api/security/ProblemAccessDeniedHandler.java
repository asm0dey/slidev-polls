package site.asm0dey.slidev.polls.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import site.asm0dey.slidev.polls.api.error.Problem;
import site.asm0dey.slidev.polls.api.error.ProblemCode;
import site.asm0dey.slidev.polls.api.logging.CorrelationIdFilter;

/**
 * Translates Spring Security authorisation failures (including CSRF rejections) into a {@link
 * Problem} JSON body with code {@code FORBIDDEN}. Complements {@link
 * ProblemAuthenticationEntryPoint} so every 4xx out of the security chain has the same envelope
 * shape.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  public ProblemAccessDeniedHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
      throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    Problem body =
        new Problem(ProblemCode.FORBIDDEN, "access denied", MDC.get(CorrelationIdFilter.MDC_KEY));
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
