package site.asm0dey.slidev.polls.core.event;

import java.time.Instant;
import java.util.UUID;

/** Emitted by {@code PollService.clearVotesForOwner} after the vote rows are gone. */
public record PollVotesClearedEvent(UUID pollId, Instant occurredAt) {}
