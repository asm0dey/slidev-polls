package site.asm0dey.slidev.polls.api.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.MDC;
import site.asm0dey.slidev.polls.api.error.Problem;
import site.asm0dey.slidev.polls.api.error.ProblemCode;

/**
 * Writes a {@link Problem} JSON envelope as an authentication challenge body straight to the Vert.x
 * response. The {@code HttpAuthenticationMechanism} contract on Quarkus 3.15.x exposes {@code
 * ChallengeData}, which only carries a status and a single header — it cannot carry a body. The
 * cleanest way to emit our JSON envelope is therefore to override {@code sendChallenge} and write
 * the body directly here, returning {@code true} so the framework treats the challenge as sent.
 *
 * <p>The {@code correlationId} is read from the JBoss-logging {@code MDC} (Phase 5 wires the filter
 * that populates it); reading a key that is not yet set simply yields {@code null}, which the
 * {@link Problem} envelope omits via {@code @JsonInclude(NON_NULL)}.
 */
final class ProblemChallengeWriter {

  /** Must match the key the Phase 5 correlation-id filter writes to the MDC. */
  static final String MDC_CORRELATION_KEY = "correlationId";

  private ProblemChallengeWriter() {}

  static Uni<Boolean> send(
      RoutingContext context, ObjectMapper mapper, int status, ProblemCode code, String message) {
    Object correlationId = MDC.get(MDC_CORRELATION_KEY);
    Problem problem =
        new Problem(code, message, correlationId == null ? null : correlationId.toString());
    byte[] body;
    try {
      // Jackson 2 (the version Quarkus 3.15 ships) declares writeValueAsBytes as throwing the
      // checked JsonProcessingException; serialising a fixed-shape Problem record never trips it.
      body = mapper.writeValueAsBytes(problem);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to serialise Problem challenge body", e);
    }
    HttpServerResponse response = context.response();
    response.setStatusCode(status);
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
    return Uni.createFrom()
        .completionStage(response.end(Buffer.buffer(body)).toCompletionStage())
        .map(ignored -> Boolean.TRUE);
  }
}
