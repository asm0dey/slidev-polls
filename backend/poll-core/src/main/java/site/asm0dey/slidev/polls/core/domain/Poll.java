package site.asm0dey.slidev.polls.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Presenter-authored poll. Owns one or more {@link Question}s; at most one question may be ACTIVE
 * at a time (FR-004, enforced by partial unique index on {@code poll_questions}).
 *
 * <p>{@code style} is a free-form theme map persisted as jsonb. {@code activeQuestionId} mirrors
 * the {@code polls.active_question_id} pointer and is {@code null} when no question is active.
 */
public record Poll(
    UUID id,
    String ownerUsername,
    String title,
    String slug,
    PollStatus status,
    Map<String, Object> style,
    UUID activeQuestionId,
    List<Question> questions,
    Instant createdAt,
    Instant updatedAt) {}
