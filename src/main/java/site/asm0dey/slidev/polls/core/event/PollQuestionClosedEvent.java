package site.asm0dey.slidev.polls.core.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the currently-active question on a poll transitions to {@code CLOSED} with no
 * replacement (the presenter explicitly closes without activating a successor). A re-activation
 * flow instead emits {@link PollActiveQuestionChangedEvent} carrying the new active question id.
 */
public record PollQuestionClosedEvent(UUID pollId, UUID questionId, Instant occurredAt) {}
