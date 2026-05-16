package site.asm0dey.slidev.polls.realtime.sse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import site.asm0dey.slidev.polls.core.service.VoteRepository;

/**
 * Builds the {@code snapshot} SSE payload for a given poll. Pulled out of {@code TallyBroadcaster}
 * so {@code StreamController} can emit the initial snapshot on connect through the same code path,
 * keeping the waiting-state ({@code activeQuestion == null}) handling in one place.
 */
@Component
public class SnapshotBuilder {

  private final PollRepository polls;
  private final VoteRepository votes;

  public SnapshotBuilder(PollRepository polls, VoteRepository votes) {
    this.polls = polls;
    this.votes = votes;
  }

  /** Build the snapshot for {@code pollId}, or empty when the poll does not exist. */
  public Optional<SnapshotPayload> build(UUID pollId) {
    return polls.findById(pollId).map(poll -> build(poll, Instant.now()));
  }

  /**
   * Build a snapshot keyed to a specific question (regardless of its lifecycle status), or empty
   * when the question is not part of the poll. Used by the anonymous historical-snapshot endpoint
   * so panels pinned to a CLOSED question can still render the question text and tally.
   */
  public Optional<SnapshotPayload> buildForQuestion(UUID pollId, UUID questionId) {
    return polls
        .findById(pollId)
        .flatMap(poll -> buildForQuestion(poll, questionId, Instant.now()));
  }

  public Optional<SnapshotPayload> buildForQuestion(Poll poll, UUID questionId, Instant emittedAt) {
    Question target =
        poll.questions().stream().filter(q -> q.id().equals(questionId)).findFirst().orElse(null);
    if (target == null) {
      return Optional.empty();
    }
    return Optional.of(buildFor(poll, target, emittedAt));
  }

  /**
   * Build a snapshot directly from a hydrated {@link Poll} — shaves a round-trip when the caller
   * already has it.
   */
  public SnapshotPayload build(Poll poll, Instant emittedAt) {
    UUID activeId = poll.activeQuestionId();
    if (activeId == null) {
      return new SnapshotPayload(poll.id(), poll.slug(), null, List.of(), 0L, emittedAt);
    }
    Question active =
        poll.questions().stream().filter(q -> q.id().equals(activeId)).findFirst().orElse(null);
    if (active == null) {
      // Defensive: should not happen, but if the pointer desynced we degrade to waiting state
      // rather than crash the fan-out (Principle IV).
      return new SnapshotPayload(poll.id(), poll.slug(), null, List.of(), 0L, emittedAt);
    }
    return buildFor(poll, active, emittedAt);
  }

  private SnapshotPayload buildFor(Poll poll, Question question, Instant emittedAt) {
    List<SnapshotPayload.ActiveOption> options = new ArrayList<>(question.options().size());
    for (Option o : question.options()) {
      options.add(new SnapshotPayload.ActiveOption(o.id(), o.label(), o.position()));
    }
    Map<UUID, Long> tally = votes.tally(question.id());
    List<SnapshotPayload.TallyEntry> tallyEntries = new ArrayList<>(question.options().size());
    for (Option o : question.options()) {
      tallyEntries.add(new SnapshotPayload.TallyEntry(o.id(), tally.getOrDefault(o.id(), 0L)));
    }
    SnapshotPayload.ActiveQuestion dto =
        new SnapshotPayload.ActiveQuestion(
            question.id(),
            question.prompt(),
            question.ordinal(),
            question.minSelections(),
            question.maxSelections(),
            options);
    long voterCount = votes.voterCount(question.id());
    return new SnapshotPayload(poll.id(), poll.slug(), dto, tallyEntries, voterCount, emittedAt);
  }
}
