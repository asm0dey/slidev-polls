package site.asm0dey.slidev.polls.persistence;

import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_QUESTIONS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.VOTES;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.jooq.impl.DSL;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.error.AlreadyVotedException;
import site.asm0dey.slidev.polls.core.error.QuestionNotActiveException;
import site.asm0dey.slidev.polls.core.service.VoteRepository;

/**
 * jOOQ-backed implementation of {@link VoteRepository}. The non-trivial piece is {@link #insert}:
 * the write is an {@code INSERT ... SELECT} gated on {@code poll_questions.status = 'ACTIVE'}, so a
 * concurrent {@code ACTIVE → CLOSED} transition flowing through the same transaction timeline sees
 * the status flip and produces zero inserted rows — which this method translates into {@link
 * QuestionNotActiveException}, matching the FR-010 / {@code @TS-025} contract. Unique-constraint
 * violations on {@code (question_id, voter_token)} are translated to {@link AlreadyVotedException}
 * in the same way {@link PollRepositoryImpl} handles the partial activation index ({@code
 * @TS-023}, {@code @TS-024}).
 */
@Repository
public class VoteRepositoryImpl implements VoteRepository {

  private final DSLContext dsl;

  public VoteRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Vote insert(Vote vote) {
    OffsetDateTime createdAt =
        vote.createdAt() != null
            ? OffsetDateTime.ofInstant(vote.createdAt(), java.time.ZoneOffset.UTC)
            : OffsetDateTime.now();
    int inserted;
    try {
      inserted =
          dsl.insertInto(
                  VOTES,
                  VOTES.ID,
                  VOTES.POLL_ID,
                  VOTES.QUESTION_ID,
                  VOTES.OPTION_ID,
                  VOTES.VOTER_TOKEN,
                  VOTES.CREATED_AT)
              .select(
                  dsl.select(
                          DSL.val(vote.id()),
                          DSL.val(vote.pollId()),
                          DSL.val(vote.questionId()),
                          DSL.val(vote.optionId()),
                          DSL.val(vote.voterToken()),
                          DSL.val(createdAt))
                      .from(POLL_QUESTIONS)
                      .where(
                          POLL_QUESTIONS
                              .ID
                              .eq(vote.questionId())
                              .and(POLL_QUESTIONS.STATUS.eq(QuestionStatus.ACTIVE.name()))))
              .execute();
    } catch (IntegrityConstraintViolationException | DataIntegrityViolationException e) {
      throw new AlreadyVotedException(
          "vote already recorded for question " + vote.questionId());
    }
    if (inserted == 0) {
      throw new QuestionNotActiveException(
          "question " + vote.questionId() + " is not ACTIVE");
    }
    return new Vote(
        vote.id(),
        vote.pollId(),
        vote.questionId(),
        vote.optionId(),
        vote.voterToken(),
        createdAt.toInstant());
  }

  @Override
  public boolean alreadyVoted(UUID questionId, String voterToken) {
    return dsl.selectOne()
        .from(VOTES)
        .where(VOTES.QUESTION_ID.eq(questionId).and(VOTES.VOTER_TOKEN.eq(voterToken)))
        .fetchOptional()
        .isPresent();
  }

  @Override
  public Map<UUID, Long> tally(UUID questionId) {
    Map<UUID, Long> out = new HashMap<>();
    dsl.select(VOTES.OPTION_ID, DSL.count())
        .from(VOTES)
        .where(VOTES.QUESTION_ID.eq(questionId))
        .groupBy(VOTES.OPTION_ID)
        .fetch()
        .forEach(r -> out.put(r.value1(), (long) r.value2()));
    return out;
  }
}
