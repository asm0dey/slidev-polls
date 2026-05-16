package site.asm0dey.slidev.polls.core.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.error.AlreadyVotedException;
import site.asm0dey.slidev.polls.core.error.QuestionNotActiveException;

/**
 * Persistence contract for the {@code votes} table. Lives in {@code poll-core} so {@link
 * VoteService} can be unit-tested without Spring or jOOQ; the production implementation lives in
 * {@code poll-persistence}.
 */
public interface VoteRepository {

  /**
   * Insert a new vote. The insert MUST only succeed when the target question is {@code ACTIVE} at
   * the moment the write executes — that is how FR-010 is enforced at the storage level (a
   * concurrent {@code ACTIVE → CLOSED} transition rolls back any in-flight inserts it observes).
   *
   * @throws AlreadyVotedException when the unique index on {@code (question_id, voter_token)}
   *     refuses a second row for the same (question, token) pair ({@code @TS-023}, {@code @TS-024})
   * @throws QuestionNotActiveException when the question is not {@code ACTIVE} ({@code @TS-025})
   */
  Vote insert(Vote vote);

  /** Best-effort hint for the {@code PublicPollView.alreadyVoted} field ({@code @TS-020}). */
  boolean alreadyVoted(UUID questionId, String voterToken);

  /**
   * Live aggregate projection — {@code option_id → count} — backing the {@code snapshot} SSE event
   * and the backoffice results view. Empty map when no votes have been recorded yet.
   */
  Map<UUID, Long> tally(UUID questionId);

  /** Delete every row in {@code votes} bound to {@code pollId}. Returns the rows deleted. */
  int deleteForPoll(UUID pollId);

  /**
   * Delete the vote cast by {@code voterToken} on {@code questionId}, only when the question is
   * still {@code ACTIVE} at delete time. Mirror of the status guard on {@link #insert}: a
   * concurrent {@code ACTIVE → CLOSED} transition observed by the same statement deletes zero rows,
   * which the caller translates into {@link
   * site.asm0dey.slidev.polls.core.error.QuestionNotActiveException}.
   *
   * @return the {@code option_ids} ballot of the deleted row when a row was deleted; {@link
   *     java.util.Optional#empty()} when the voter had no vote on this question (idempotent no-op)
   *     — the caller distinguishes this from the not-ACTIVE case by checking the question status
   *     before attempting the delete
   * @throws site.asm0dey.slidev.polls.core.error.QuestionNotActiveException when the question is
   *     not {@code ACTIVE} at delete time
   */
  java.util.Optional<List<UUID>> deleteByQuestionAndVoter(UUID questionId, String voterToken);
}
