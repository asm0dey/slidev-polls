package site.asm0dey.slidev.polls.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * RFC-7807-style error envelope serialised as the response body of every non-2xx API response.
 * {@code code} carries the stable machine-readable category; {@code message} is the non-technical,
 * presenter- or respondent- facing text; {@code correlationId} ties the response to the MDC value
 * set by {@code CorrelationIdFilter} so log lines and the surfaced error share a lookup key.
 *
 * <p>{@code errors} carries per-field validation messages for {@code VALIDATION_FAILED}; null on
 * every other code so the envelope shape stays compact for non-validation failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
    ProblemCode code, String message, String correlationId, Map<String, List<String>> errors) {

  public Problem(ProblemCode code, String message, String correlationId) {
    this(code, message, correlationId, null);
  }
}
