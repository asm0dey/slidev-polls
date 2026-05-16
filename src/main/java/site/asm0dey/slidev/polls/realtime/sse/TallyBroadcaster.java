package site.asm0dey.slidev.polls.realtime.sse;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
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
@Component
public class TallyBroadcaster {

  private final SseHub hub;
  private final SnapshotBuilder snapshots;

  public TallyBroadcaster(SseHub hub, SnapshotBuilder snapshots) {
    this.hub = hub;
    this.snapshots = snapshots;
  }

  @EventListener
  public void onVoteCast(VoteCastEvent event) {
    resnapshotForQuestion(event.pollId(), event.questionId());
  }

  @EventListener
  public void onVoteRetracted(VoteRetractedEvent event) {
    resnapshotForQuestion(event.pollId(), event.questionId());
  }

  @EventListener
  public void onActiveQuestionChanged(PollActiveQuestionChangedEvent event) {
    snapshots
        .build(event.pollId())
        .ifPresent(payload -> hub.broadcast(event.pollId(), "snapshot", payload));
  }

  @EventListener
  public void onQuestionClosed(PollQuestionClosedEvent event) {
    hub.broadcast(
        event.pollId(),
        "question-closed",
        new QuestionClosedPayload(event.pollId(), event.questionId(), Instant.now()));
  }

  @EventListener
  public void onVotesCleared(PollVotesClearedEvent event) {
    snapshots
        .build(event.pollId())
        .ifPresent(payload -> hub.broadcast(event.pollId(), "snapshot", payload));
  }

  private void resnapshotForQuestion(UUID pollId, UUID questionId) {
    // SnapshotBuilder produces the canonical wire payload used on (re)connect — re-emitting it
    // after every ballot change keeps the client purely snapshot-driven (no delta application).
    snapshots
        .buildForQuestion(pollId, questionId)
        .ifPresent(payload -> hub.broadcast(pollId, "snapshot", payload));
  }
}
