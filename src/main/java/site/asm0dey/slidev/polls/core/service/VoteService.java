package site.asm0dey.slidev.polls.core.service;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.error.QuestionNotActiveException;
import site.asm0dey.slidev.polls.core.event.VoteCastEvent;

/**
 * Anonymous voting flow. Resolves slug → poll → active question, checks that the submitted option
 * belongs to that question, and delegates the write to {@link VoteRepository#insert(Vote)} — whose
 * {@code INSERT ... SELECT} guard is the FR-010 storage-level enforcement of the "question must be
 * ACTIVE at insert time" invariant.
 *
 * <p>On a successful insert the service publishes a {@link VoteCastEvent} whose {@code
 * newOptionCount} already includes the row just written; the listener side of this (T111's {@code
 * TallyBroadcaster}) fans the delta out over SSE.
 */
@Service
public class VoteService {

  private final PollRepository pollRepository;
  private final VoteRepository voteRepository;
  private final ApplicationEventPublisher events;

  public VoteService(
      PollRepository pollRepository,
      VoteRepository voteRepository,
      ApplicationEventPublisher events) {
    this.pollRepository = pollRepository;
    this.voteRepository = voteRepository;
    this.events = events;
  }

  /**
   * Record a vote on the currently-active question of the poll identified by {@code slug}.
   *
   * @throws NotFoundException when the slug does not resolve to a poll, or when the submitted
   *     option does not belong to the active question
   * @throws QuestionNotActiveException when the poll has no active question, or the active question
   *     transitioned to CLOSED between the check and the insert ({@code @TS-025})
   * @throws site.asm0dey.slidev.polls.core.error.AlreadyVotedException when the unique index on
   *     {@code (question_id, voter_token)} refuses a second row ({@code @TS-023}, {@code @TS-024})
   */
  @Transactional
  public Vote recordVote(String slug, UUID optionId, String voterToken) {
    Poll poll = pollRepository.findBySlug(slug).orElseThrow(() -> new NotFoundException(slug));
    UUID activeQuestionId = getActiveQuestionId(slug, poll);
    Question activeQuestion =
        poll.questions().stream()
            .filter(q -> q.id().equals(activeQuestionId))
            .findFirst()
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "active question " + activeQuestionId + " not in poll " + poll.id()));
    boolean optionBelongs =
        activeQuestion.options().stream().map(Option::id).anyMatch(id -> id.equals(optionId));
    if (!optionBelongs) {
      throw new NotFoundException(
          "option " + optionId + " does not belong to active question " + activeQuestionId);
    }

    Vote pending =
        new Vote(
            UUID.randomUUID(), poll.id(), activeQuestionId, optionId, voterToken, Instant.now());
    Vote stored = voteRepository.insert(pending);

    long newCount = voteRepository.tally(activeQuestionId).getOrDefault(optionId, 0L);
    events.publishEvent(
        new VoteCastEvent(poll.id(), activeQuestionId, optionId, newCount, stored.createdAt()));
    return stored;
  }

  private static @NonNull UUID getActiveQuestionId(String slug, Poll poll) {
    UUID activeQuestionId = poll.activeQuestionId();
    if (activeQuestionId == null) {
      // Early-exit before the wasted round-trip to VoteRepository.insert. The repo-level
      // INSERT ... SELECT would also return zero rows and throw QuestionNotActiveException,
      // but catching the trivial case here keeps error classification cheap and avoids a
      // misleading stack trace from a query that never had a chance to succeed.
      throw new QuestionNotActiveException("poll " + slug + " has no active question");
    }
    return activeQuestionId;
  }

  /** Best-effort hint used to populate {@code PublicPollView.alreadyVoted}. */
  @Transactional(readOnly = true)
  public boolean alreadyVoted(UUID questionId, String voterToken) {
    if (questionId == null || voterToken == null || voterToken.isBlank()) {
      return false;
    }
    return voteRepository.alreadyVoted(questionId, voterToken);
  }
}
