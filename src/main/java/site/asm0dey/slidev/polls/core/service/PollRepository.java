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

  /**
   * Reconcile the questions list for {@code pollId}: questions with an {@code id} already on the
   * poll are updated in place (prompt, ordinal, options diffed), questions with a null id are
   * inserted with a fresh UUID, and any existing question whose id is not in {@code incoming} is
   * deleted. Cascades onto {@code poll_options} and {@code votes} via FK ON DELETE CASCADE — that
   * is intended only for explicit removals, never for unchanged questions.
   */
  Poll replaceQuestions(UUID pollId, List<CreatePollCommand.QuestionUpdate> incoming);

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

  /**
   * Replace the allowed-origins list for {@code pollId}. A non-null (even empty) list replaces the
   * current value. Throws {@link site.asm0dey.slidev.polls.core.error.NotFoundException} when the
   * poll does not exist.
   */
  Poll updateAllowedOrigins(UUID pollId, List<String> origins);

  /**
   * True iff some poll's {@code allowed_origins} array contains {@code origin} verbatim. Used by
   * the per-poll CORS resolver for pre-auth deck-login preflight where no path or header identifies
   * a single poll. Implemented as a single existence query so the resolver does not pay for
   * question / option hydration on every preflight.
   */
  boolean isOriginAllowedByAnyPoll(String origin);

  /**
   * Transition every question on {@code pollId} back to {@code DRAFT}, clearing {@code
   * activated_at} and {@code closed_at}; null {@code polls.active_question_id} and set {@code
   * polls.status = DRAFT}. Idempotent.
   */
  Poll resetQuestionsToDraft(UUID pollId);

  /**
   * Distinct-voter ballot counts per question on {@code pollId}. Missing questions (no votes yet)
   * are simply absent from the returned map — callers default to {@code 0}. Used by the admin DTO
   * assembler to surface {@code voteCount} on every question and by the structural-edit lock to
   * decide whether a question is still safe to mutate (FR-013, RESOURCE_HAS_VOTES).
   */
  Map<UUID, Long> voteCountByQuestion(UUID pollId);
}
