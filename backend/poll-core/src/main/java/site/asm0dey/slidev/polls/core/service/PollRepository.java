package site.asm0dey.slidev.polls.core.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Poll;

/**
 * Persistence contract for the poll aggregate. Lives in {@code poll-core} so {@link PollService}
 * can be unit-tested without Spring or jOOQ; the production implementation lives in {@code
 * poll-persistence}.
 */
public interface PollRepository {

  /**
   * Insert a new poll aggregate (with its questions and options). Returns the hydrated aggregate.
   */
  Poll insert(Poll poll);

  Optional<Poll> findById(UUID pollId);

  Optional<Poll> findBySlug(String slug);

  List<Poll> findByOwner(String ownerUsername);

  /** Case-insensitive slug lookup; excludes poll {@code excludingPollId} when non-null. */
  boolean slugTaken(String slug, UUID excludingPollId);

  /** Replace poll header fields (title, slug) — not questions. */
  Poll updateHeader(UUID pollId, String title, String slug);

  /** Replace the questions/options list for {@code pollId} in one transaction. */
  Poll replaceQuestions(UUID pollId, List<CreatePollCommand.QuestionDraft> questions);

  Poll updateStyle(UUID pollId, Map<String, Object> style);

  void delete(UUID pollId);

  /**
   * Atomically close any currently-ACTIVE question on {@code pollId} and mark {@code questionId}
   * ACTIVE. The partial unique index on {@code poll_questions(poll_id) WHERE status = 'ACTIVE'} is
   * the storage-level invariant (FR-004, {@code @TS-004}); implementations MUST surface
   * unique-constraint races as a distinct exception type so the service can translate them.
   */
  Poll activateQuestion(UUID pollId, UUID questionId);

  /** Close the currently-ACTIVE question on {@code pollId}. No-op when none is active. */
  Poll closeActiveQuestion(UUID pollId);
}
