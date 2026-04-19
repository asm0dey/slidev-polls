package site.asm0dey.slidev.polls.core.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a successful {@code DRAFT → ACTIVE} transition on a poll, including the implicit
 * close of the previously-active question (FR-004 — at most one ACTIVE per poll).
 *
 * <p>Subscribers (e.g. {@code TallyBroadcaster}) re-fetch the poll and rebuild the {@code snapshot}
 * SSE event from live state; the event itself only carries the identifiers so it can be routed.
 */
public record PollActiveQuestionChangedEvent(
    UUID pollId, UUID newActiveQuestionId, Instant occurredAt) {}
