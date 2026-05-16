package site.asm0dey.slidev.polls.core.event;

import java.time.Instant;
import java.util.UUID;

public record VoteRetractedEvent(UUID pollId, UUID questionId, Instant emittedAt) {}
