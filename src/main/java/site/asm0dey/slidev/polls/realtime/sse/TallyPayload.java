package site.asm0dey.slidev.polls.realtime.sse;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape of the {@code tally} SSE event — see {@code contracts/sse-events.md}. {@code count}
 * carries the new absolute tally for {@code optionId}, not a delta.
 */
public record TallyPayload(
    UUID pollId, UUID questionId, UUID optionId, long count, Instant emittedAt) {}
