package site.asm0dey.slidev.polls.api.logging;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import org.jboss.logging.MDC;

/**
 * Attaches a per-request {@code correlationId} to the JBoss-logging MDC and mirrors it on the
 * response headers so presenters who see a 500 in the browser can grep the same id in server logs
 * (Principle VI). The id is taken from an incoming {@code X-Correlation-Id} header when the caller
 * supplies one — useful for tracing voter → SSE → backoffice chains — otherwise a fresh UUID is
 * minted.
 *
 * <p>Uses {@link MDC#put} under {@link #MDC_KEY} ({@code "correlationId"}) — the same key {@code
 * SecurityProblemMappers} / {@code ProblemChallengeWriter} and {@code DomainExceptionMappers} read,
 * so the log line and the surfaced {@code Problem} envelope share a lookup key. The request filter
 * assigns it; the response filter writes it to the header and clears the MDC entry so it does not
 * leak onto a recycled worker thread.
 */
@Provider
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

  public static final String MDC_KEY = "correlationId";
  public static final String HEADER = "X-Correlation-Id";

  @Override
  public void filter(ContainerRequestContext requestContext) {
    String correlationId = requestContext.getHeaderString(HEADER);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }
    MDC.put(MDC_KEY, correlationId);
    requestContext.setProperty(MDC_KEY, correlationId);
  }

  @Override
  public void filter(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
    try {
      Object correlationId = requestContext.getProperty(MDC_KEY);
      if (correlationId == null) {
        correlationId = MDC.get(MDC_KEY);
      }
      if (correlationId != null) {
        responseContext.getHeaders().putSingle(HEADER, correlationId);
      }
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
