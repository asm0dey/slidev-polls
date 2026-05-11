package site.asm0dey.slidev.polls.realtime.sse;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape of the {@code question-closed} SSE event — see {@code contracts/sse-events.md}.
 * Emitted when the presenter closes an active question without activating a successor. A subsequent
 * {@code snapshot} will arrive once a new question becomes active.
 */
public record QuestionClosedPayload(UUID pollId, UUID questionId, Instant emittedAt) {}
