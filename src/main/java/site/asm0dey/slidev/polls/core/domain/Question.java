package site.asm0dey.slidev.polls.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single question within a poll. {@code ordinal} controls the presenter-authored order.
 * Lifecycle: DRAFT → ACTIVE → CLOSED; DRAFT → CLOSED is also permitted. {@code activatedAt} and
 * {@code closedAt} mirror the transitions. {@code minSelections}/{@code maxSelections} define the
 * per-question ballot arity: a voter must pick at least {@code minSelections} and at most {@code
 * maxSelections} options.
 */
public record Question(
    UUID id,
    UUID pollId,
    String prompt,
    int ordinal,
    QuestionStatus status,
    int minSelections,
    int maxSelections,
    List<Option> options,
    Instant activatedAt,
    Instant closedAt) {

  public Question {
    if (maxSelections < 1) {
      throw new IllegalArgumentException("maxSelections must be ≥ 1");
    }
    if (minSelections < 0) {
      throw new IllegalArgumentException("minSelections must be ≥ 0");
    }
    if (minSelections > maxSelections) {
      throw new IllegalArgumentException(
          "minSelections (" + minSelections + ") > maxSelections (" + maxSelections + ")");
    }
  }
}
