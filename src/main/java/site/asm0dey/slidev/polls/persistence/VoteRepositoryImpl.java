package site.asm0dey.slidev.polls.persistence;

import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_QUESTIONS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.VOTES;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * in the same way {@link PollRepositoryImpl} handles the partial activation index ({@code @TS-023},
 * {@code @TS-024}).
 *
 * <p>The ballot itself rides in {@code option_ids uuid[]}; an empty array is a valid "abstain"
 * ballot and still occupies a row, so the unique index keeps refusing second submissions even from
 * abstainers. The aggregate projection in {@link #tally} unnests the array so a single multi-select
 * ballot contributes a count to every option it picked.
 *
 * <p>{@link #deleteByQuestionAndVoter} is the symmetric path on the delete side: a gated {@code
 * DELETE … WHERE EXISTS (… status='ACTIVE')} translates a zero-rows-affected race against the
 * status flip into {@link QuestionNotActiveException}. The method first reads the row's {@code
 * option_ids} so the caller can publish a tally event; if a concurrent transaction deletes the row
 * between the preflight and the gated DELETE, we re-read the question status to disambiguate "row
 * vanished concurrently" (idempotent no-op) from "question flipped to CLOSED" (FR-010 violation).
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
    UUID[] optionIdArray = vote.optionIds().toArray(new UUID[0]);
    int inserted;
    try {
      inserted =
          dsl.insertInto(
                  VOTES,
                  VOTES.ID,
                  VOTES.POLL_ID,
                  VOTES.QUESTION_ID,
                  VOTES.OPTION_IDS,
                  VOTES.VOTER_TOKEN,
                  VOTES.CREATED_AT)
              .select(
                  dsl.select(
                          DSL.val(vote.id()),
                          DSL.val(vote.pollId()),
                          DSL.val(vote.questionId()),
                          DSL.val(optionIdArray, VOTES.OPTION_IDS.getDataType()),
                          DSL.val(vote.voterToken()),
                          DSL.val(createdAt))
                      .from(POLL_QUESTIONS)
                      .where(
                          POLL_QUESTIONS
                              .ID
                              .eq(vote.questionId())
                              .and(POLL_QUESTIONS.STATUS.eq(QuestionStatus.ACTIVE.name()))))
              .execute();
    } catch (IntegrityConstraintViolationException | DataIntegrityViolationException _) {
      throw new AlreadyVotedException("vote already recorded for question " + vote.questionId());
    }
    if (inserted == 0) {
      throw new QuestionNotActiveException("question " + vote.questionId() + " is not ACTIVE");
    }
    return new Vote(
        vote.id(),
        vote.pollId(),
        vote.questionId(),
        List.of(optionIdArray),
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
    // Fetch ballots as raw arrays and tally in-process. H2 supports UNNEST only as a constant
    // table-valued source (no implicit lateral join to outer columns), and the cross-dialect
    // alternatives (CROSS JOIN LATERAL, explicit unnest derived table) are not parsed by H2's
    // SQL parser. A read-side fanout in Java sidesteps the portability gap; the row volume per
    // question is bounded by the active voter count, which is the same upper bound the index
    // scan would have hit anyway.
    Map<UUID, Long> out = new HashMap<>();
    dsl.select(VOTES.OPTION_IDS)
        .from(VOTES)
        .where(VOTES.QUESTION_ID.eq(questionId))
        .fetch()
        .forEach(
            r -> {
              UUID[] ids = r.value1();
              if (ids == null) {
                return;
              }
              for (UUID id : ids) {
                out.merge(id, 1L, Long::sum);
              }
            });
    return out;
  }

  @Override
  public int deleteForPoll(UUID pollId) {
    return dsl.deleteFrom(VOTES).where(VOTES.POLL_ID.eq(pollId)).execute();
  }

  @Override
  public Optional<List<UUID>> deleteByQuestionAndVoter(UUID questionId, String voterToken) {
    UUID[] optionIds =
        dsl.select(VOTES.OPTION_IDS)
            .from(VOTES)
            .where(VOTES.QUESTION_ID.eq(questionId).and(VOTES.VOTER_TOKEN.eq(voterToken)))
            .fetchOne(VOTES.OPTION_IDS);
    if (optionIds == null) {
      return Optional.empty();
    }

    int deleted =
        dsl.deleteFrom(VOTES)
            .where(
                VOTES
                    .QUESTION_ID
                    .eq(questionId)
                    .and(VOTES.VOTER_TOKEN.eq(voterToken))
                    .and(
                        DSL.exists(
                            dsl.selectOne()
                                .from(POLL_QUESTIONS)
                                .where(
                                    POLL_QUESTIONS
                                        .ID
                                        .eq(questionId)
                                        .and(
                                            POLL_QUESTIONS.STATUS.eq(
                                                QuestionStatus.ACTIVE.name()))))))
            .execute();
    if (deleted == 0) {
      // Disambiguate: either the question flipped to CLOSED (FR-010 enforcement) or
      // a concurrent transaction deleted the row between our preflight and our DELETE
      // (two-tab retract race, deleteForPoll). Re-read status to decide.
      String status =
          dsl.select(POLL_QUESTIONS.STATUS)
              .from(POLL_QUESTIONS)
              .where(POLL_QUESTIONS.ID.eq(questionId))
              .fetchOne(POLL_QUESTIONS.STATUS);
      if (status == null || !QuestionStatus.ACTIVE.name().equals(status)) {
        throw new QuestionNotActiveException("question " + questionId + " is not ACTIVE");
      }
      // Question still ACTIVE but row gone — idempotent no-op.
      return Optional.empty();
    }
    return Optional.of(List.of(optionIds));
  }
}
