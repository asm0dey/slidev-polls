package site.asm0dey.slidev.polls.realtime.sse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Instant;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.event.PollActiveQuestionChangedEvent;
import site.asm0dey.slidev.polls.core.event.PollQuestionClosedEvent;
import site.asm0dey.slidev.polls.core.event.PollVotesClearedEvent;
import site.asm0dey.slidev.polls.core.event.VoteCastEvent;
import site.asm0dey.slidev.polls.core.event.VoteRetractedEvent;
import site.asm0dey.slidev.polls.realtime.SseHub;

/**
 * Fans poll state changes out to SSE subscribers. Every ballot change re-broadcasts the canonical
 * {@code snapshot} payload — there is no longer a delta event on the wire. Listens for these {@code
 * poll-core} events:
 *
 * <ul>
 *   <li>{@link VoteCastEvent} → fresh {@code snapshot} with the new tally for the voted question
 *   <li>{@link VoteRetractedEvent} → fresh {@code snapshot} with the post-retraction tally
 *   <li>{@link PollActiveQuestionChangedEvent} → fresh {@code snapshot} with the new active
 *       question and a zeroed tally ({@code @TS-031})
 *   <li>{@link PollQuestionClosedEvent} → {@code question-closed} SSE event
 *   <li>{@link PollVotesClearedEvent} → fresh {@code snapshot} reflecting the cleared state
 * </ul>
 *
 * <p>Event routing uses the shared {@link SseHub}; per-emitter send failures are isolated there, so
 * a dead browser on one pollId cannot starve siblings or crash the broadcast (Principle IV).
 */
@ApplicationScoped
public class TallyBroadcaster {

  private static final String SNAPSHOT_EVENT = "snapshot";

  private final SseHub hub;
  private final SnapshotBuilder snapshots;

  public TallyBroadcaster(SseHub hub, SnapshotBuilder snapshots) {
    this.hub = hub;
    this.snapshots = snapshots;
  }

  public void onVoteCast(@Observes VoteCastEvent event) {
    resnapshotForQuestion(event.pollId(), event.questionId());
  }

  public void onVoteRetracted(@Observes VoteRetractedEvent event) {
    resnapshotForQuestion(event.pollId(), event.questionId());
  }

  public void onActiveQuestionChanged(@Observes PollActiveQuestionChangedEvent event) {
    snapshots
        .build(event.pollId())
        .ifPresent(payload -> hub.broadcast(event.pollId(), SNAPSHOT_EVENT, payload));
  }

  public void onQuestionClosed(@Observes PollQuestionClosedEvent event) {
    hub.broadcast(
        event.pollId(),
        "question-closed",
        new QuestionClosedPayload(event.pollId(), event.questionId(), Instant.now()));
  }

  public void onVotesCleared(@Observes PollVotesClearedEvent event) {
    snapshots
        .build(event.pollId())
        .ifPresent(payload -> hub.broadcast(event.pollId(), SNAPSHOT_EVENT, payload));
  }

  private void resnapshotForQuestion(UUID pollId, UUID questionId) {
    // SnapshotBuilder produces the canonical wire payload used on (re)connect — re-emitting it
    // after every ballot change keeps the client purely snapshot-driven (no delta application).
    snapshots
        .buildForQuestion(pollId, questionId)
        .ifPresent(payload -> hub.broadcast(pollId, SNAPSHOT_EVENT, payload));
  }
}
