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
   * Build a snapshot directly from a hydrated {@link Poll} — shaves a round-trip when the caller
   * already has it.
   */
  public SnapshotPayload build(Poll poll, Instant emittedAt) {
    UUID activeId = poll.activeQuestionId();
    if (activeId == null) {
      return new SnapshotPayload(poll.id(), poll.slug(), null, List.of(), emittedAt);
    }
    Question active =
        poll.questions().stream().filter(q -> q.id().equals(activeId)).findFirst().orElse(null);
    if (active == null) {
      // Defensive: should not happen, but if the pointer desynced we degrade to waiting state
      // rather than crash the fan-out (Principle IV).
      return new SnapshotPayload(poll.id(), poll.slug(), null, List.of(), emittedAt);
    }
    List<SnapshotPayload.ActiveOption> options = new ArrayList<>(active.options().size());
    for (Option o : active.options()) {
      options.add(new SnapshotPayload.ActiveOption(o.id(), o.label(), o.position()));
    }
    Map<UUID, Long> tally = votes.tally(active.id());
    List<SnapshotPayload.TallyEntry> tallyEntries = new ArrayList<>(active.options().size());
    for (Option o : active.options()) {
      tallyEntries.add(new SnapshotPayload.TallyEntry(o.id(), tally.getOrDefault(o.id(), 0L)));
    }
    SnapshotPayload.ActiveQuestion activeDto =
        new SnapshotPayload.ActiveQuestion(active.id(), active.prompt(), active.ordinal(), options);
    return new SnapshotPayload(poll.id(), poll.slug(), activeDto, tallyEntries, emittedAt);
  }
}
