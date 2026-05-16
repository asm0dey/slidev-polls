package site.asm0dey.slidev.polls.realtime.sse;

import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import site.asm0dey.slidev.polls.core.event.PollActiveQuestionChangedEvent;
import site.asm0dey.slidev.polls.core.event.PollQuestionClosedEvent;
import site.asm0dey.slidev.polls.core.event.PollVotesClearedEvent;
import site.asm0dey.slidev.polls.core.event.VoteCastEvent;
import site.asm0dey.slidev.polls.core.event.VoteRetractedEvent;
import site.asm0dey.slidev.polls.realtime.SseHub;

/**
 * Fans poll state changes out to SSE subscribers. Listens for three {@code poll-core} events:
 *
 * <ul>
 *   <li>{@link VoteCastEvent} → {@code tally} SSE event with the new absolute count
 *       ({@code @TS-030})
 *   <li>{@link VoteRetractedEvent} → {@code tally} SSE event with the new absolute count
 *       (post-decrement)
 *   <li>{@link PollActiveQuestionChangedEvent} → fresh {@code snapshot} with the new active
 *       question and a zeroed tally ({@code @TS-031})
 *   <li>{@link PollQuestionClosedEvent} → {@code question-closed} SSE event
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
    // Task 10 will rewrite this to a full resnapshot. Placeholder no-op to unblock build.
  }

  @EventListener
  public void onVoteRetracted(VoteRetractedEvent event) {
    // Task 10 will rewrite this to a full resnapshot. Placeholder no-op to unblock build.
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
}
