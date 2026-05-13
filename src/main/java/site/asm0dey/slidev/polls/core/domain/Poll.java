package site.asm0dey.slidev.polls.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Presenter-authored poll. Owns one or more {@link Question}s; at most one question may be ACTIVE
 * at a time (FR-004, enforced by partial unique index on {@code poll_questions}).
 *
 * <p>{@code activeQuestionId} mirrors the {@code polls.active_question_id} pointer and is {@code
 * null} when no question is active.
 */
public record Poll(
    UUID id,
    String ownerUsername,
    String title,
    String slug,
    PollStatus status,
    UUID activeQuestionId,
    List<Question> questions,
    List<String> allowedOrigins,
    Instant createdAt,
    Instant updatedAt) {

  public Poll {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }
}
