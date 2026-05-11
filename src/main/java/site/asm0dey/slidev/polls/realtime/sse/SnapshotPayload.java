package site.asm0dey.slidev.polls.realtime.sse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape of the {@code snapshot} SSE event — see {@code contracts/sse-events.md}. {@code
 * activeQuestion} is null when the poll has no active question (FR-008 "waiting" state); {@code
 * tally} still lists every option of the active question with its current count (zero when the
 * question has just been activated).
 */
public record SnapshotPayload(
    UUID pollId,
    String slug,
    ActiveQuestion activeQuestion,
    List<TallyEntry> tally,
    Instant emittedAt) {

  public record ActiveQuestion(UUID id, String prompt, int ordinal, List<ActiveOption> options) {}

  public record ActiveOption(UUID id, String label, int position) {}

  public record TallyEntry(UUID optionId, long count) {}
}
