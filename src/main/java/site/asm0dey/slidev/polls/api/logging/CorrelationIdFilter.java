package site.asm0dey.slidev.polls.api.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Attaches a per-request {@code correlationId} to SLF4J MDC and mirrors it on the response headers
 * so presenters who see a 500 in the browser can grep the same id in server logs (Principle VI).
 * The id is taken from an incoming {@code X-Correlation-Id} header when the caller supplies one —
 * useful for tracing voter → SSE → backoffice chains — otherwise a fresh UUID is minted.
 *
 * <p>Runs before Spring Security so authentication failures (which are handled by the
 * exception-translation chain) still log the correlation id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String MDC_KEY = "correlationId";
  public static final String HEADER = "X-Correlation-Id";

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain chain)
      throws ServletException, IOException {
    String correlationId = request.getHeader(HEADER);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
