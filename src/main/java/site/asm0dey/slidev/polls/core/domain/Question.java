package site.asm0dey.slidev.polls.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single question within a poll. {@code ordinal} controls the presenter-authored order.
 * Lifecycle: DRAFT → ACTIVE → CLOSED; DRAFT → CLOSED is also permitted. {@code activatedAt} and
 * {@code closedAt} mirror the transitions.
 */
public record Question(
    UUID id,
    UUID pollId,
    String prompt,
    int ordinal,
    QuestionStatus status,
    List<Option> options,
    Instant activatedAt,
    Instant closedAt) {}
