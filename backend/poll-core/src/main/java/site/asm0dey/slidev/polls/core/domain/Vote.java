package site.asm0dey.slidev.polls.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A single recorded vote. Hydrated from the {@code votes} table; the {@code (question_id,
 * voter_token)} unique index is the FR-009 storage-level guard that surfaces
 * {@link site.asm0dey.slidev.polls.core.error.AlreadyVotedException} when a voter tries to cast a
 * second ballot against the same question.
 */
public record Vote(
    UUID id,
    UUID pollId,
    UUID questionId,
    UUID optionId,
    String voterToken,
    Instant createdAt) {}
