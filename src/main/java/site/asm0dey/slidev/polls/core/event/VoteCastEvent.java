package site.asm0dey.slidev.polls.core.event;

import java.time.Instant;
import java.util.UUID;

public record VoteCastEvent(UUID pollId, UUID questionId, Instant emittedAt) {}
