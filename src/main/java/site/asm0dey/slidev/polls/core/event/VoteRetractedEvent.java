package site.asm0dey.slidev.polls.core.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code VoteService.retractVote} after a vote row is deleted from the currently
 * ACTIVE question. {@code newOptionCount} is the post-delete tally of the option that was on the
 * deleted row, so the {@code tally} SSE listener can broadcast the decrement using the same payload
 * shape as {@link VoteCastEvent}.
 */
public record VoteRetractedEvent(
    UUID pollId, UUID questionId, UUID optionId, long newOptionCount, Instant occurredAt) {}
