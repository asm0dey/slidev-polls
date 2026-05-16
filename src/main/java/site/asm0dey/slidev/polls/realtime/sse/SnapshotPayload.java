package site.asm0dey.slidev.polls.realtime.sse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape of the {@code snapshot} SSE event — see {@code contracts/sse-events.md}. {@code
 * activeQuestion} is null when the poll has no active question (FR-008 "waiting" state); {@code
 * tally} still lists every option of the active question with its current count (zero when the
 * question has just been activated). {@code voterCount} is the ballots-cast figure (distinct from
 * the selections-summed totals in {@code tally}), surfaced separately so the multi-choice results
 * footer can render "{voters} voters · {selections} selections" without a second round-trip.
 */
public record SnapshotPayload(
    UUID pollId,
    String slug,
    ActiveQuestion activeQuestion,
    List<TallyEntry> tally,
    long voterCount,
    Instant emittedAt) {

  /**
   * Per-question arity rides on the snapshot so the voter UI (radio vs checkbox, Submit/Skip
   * labels) and the results panel (voters vs selections footer) can branch on it without a separate
   * fetch. Single-choice questions surface {@code (1, 1)}.
   */
  public record ActiveQuestion(
      UUID id,
      String prompt,
      int ordinal,
      int minSelections,
      int maxSelections,
      List<ActiveOption> options) {}

  public record ActiveOption(UUID id, String label, int position) {}

  public record TallyEntry(UUID optionId, long count) {}
}
