package site.asm0dey.slidev.polls.api.error;

/**
 * Stable, user-facing failure category exposed on every non-2xx response as {@link Problem#code()}.
 * The values mirror the enum in openapi.yaml's Problem schema and are the authoritative FR-017
 * distinction between failure kinds.
 */
public enum ProblemCode {
  AUTH_REQUIRED,
  FORBIDDEN,
  NOT_FOUND,
  VALIDATION_FAILED,
  ALREADY_VOTED,
  QUESTION_NOT_ACTIVE,
  ACTIVATION_REJECTED,
  SLUG_TAKEN,
  SLUG_INVALID,
  SLUG_RESERVED,
  DECK_TOKEN_INVALID,
  DECK_TOKEN_POLL_MISMATCH,
  TRANSPORT_FAILURE
}
