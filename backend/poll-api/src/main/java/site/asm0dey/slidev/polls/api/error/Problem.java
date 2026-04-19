package site.asm0dey.slidev.polls.api.error;

/**
 * RFC-7807-style error envelope serialised as the response body of every non-2xx API response.
 * {@code code} carries the stable machine-readable category; {@code message} is the non-technical,
 * presenter- or respondent- facing text; {@code correlationId} ties the response to the MDC value
 * set by {@code CorrelationIdFilter} so log lines and the surfaced error share a lookup key.
 */
public record Problem(ProblemCode code, String message, String correlationId) {}
