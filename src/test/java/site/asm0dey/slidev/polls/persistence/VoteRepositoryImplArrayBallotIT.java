package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.error.AlreadyVotedException;

/**
 * Storage-level coverage for the array-valued ballot shape introduced in Task 7. Every vote row now
 * stores its full ballot in {@code option_ids uuid[]}; the tally projection unnests that array so a
 * single ballot can contribute counts to every option it selected. Run under both Postgres and H2
 * to confirm the cross-dialect array path and the unnest aggregation behave identically.
 */
class VoteRepositoryImplArrayBallotIT extends AbstractPostgresTest {

  record P(UUID pollId, UUID questionId, List<UUID> options) {}

  abstract class CommonArrayBallot {
    protected DSLContext dsl;
    protected PollRepositoryImpl pollRepository;
    protected VoteRepositoryImpl voteRepository;

    protected abstract DSLContext dsl();

    @BeforeEach
    void setUp() {
      dsl = dsl();
      pollRepository = new PollRepositoryImpl(dsl);
      voteRepository = new VoteRepositoryImpl(dsl);
      dsl.insertInto(ADMIN_USER)
          .set(ADMIN_USER.USERNAME, "array-owner")
          .set(ADMIN_USER.PASSWORD_HASH, "n/a")
          .set(ADMIN_USER.CREATED_AT, OffsetDateTime.now())
          .onConflictDoNothing()
          .execute();
    }

    @Test
    void storesArrayBallotAsSingleRow() {
      P p = activateMultiQuestion(3, 1, 3);
      UUID a = p.options().get(0);
      UUID c = p.options().get(2);

      Vote stored =
          voteRepository.insert(
              new Vote(
                  UUID.randomUUID(),
                  p.pollId(),
                  p.questionId(),
                  List.of(a, c),
                  "voter-1",
                  Instant.now()));

      assertThat(stored.optionIds()).containsExactly(a, c);
      assertThat(voteRepository.tally(p.questionId())).containsEntry(a, 1L).containsEntry(c, 1L);
      assertThat(voteRepository.alreadyVoted(p.questionId(), "voter-1")).isTrue();
    }

    @Test
    void emptyBallotStoredAsRow() {
      P p = activateMultiQuestion(3, 0, 3);

      Vote stored =
          voteRepository.insert(
              new Vote(
                  UUID.randomUUID(),
                  p.pollId(),
                  p.questionId(),
                  List.of(),
                  "abstainer",
                  Instant.now()));

      assertThat(stored.optionIds()).isEmpty();
      assertThat(voteRepository.alreadyVoted(p.questionId(), "abstainer")).isTrue();
      assertThat(voteRepository.tally(p.questionId())).isEmpty();
    }

    @Test
    void secondBallotFromSameVoterRejected() {
      P p = activateMultiQuestion(3, 0, 3);
      UUID a = p.options().get(0);
      voteRepository.insert(
          new Vote(
              UUID.randomUUID(), p.pollId(), p.questionId(), List.of(a), "voter", Instant.now()));

      assertThatThrownBy(
              () ->
                  voteRepository.insert(
                      new Vote(
                          UUID.randomUUID(),
                          p.pollId(),
                          p.questionId(),
                          List.of(a),
                          "voter",
                          Instant.now())))
          .isInstanceOf(AlreadyVotedException.class);
    }

    private P activateMultiQuestion(int options, int min, int max) {
      UUID pollId = UUID.randomUUID();
      UUID questionId = UUID.randomUUID();
      List<Option> opts = new ArrayList<>(options);
      List<UUID> optionIds = new ArrayList<>(options);
      for (int i = 0; i < options; i++) {
        UUID oid = UUID.randomUUID();
        optionIds.add(oid);
        opts.add(new Option(oid, questionId, "opt-" + i, i));
      }
      Poll draft =
          new Poll(
              pollId,
              "array-owner",
              "Array poll",
              "array-" + pollId.toString().substring(0, 8),
              PollStatus.DRAFT,
              null,
              List.of(
                  new Question(
                      questionId,
                      pollId,
                      "Q?",
                      0,
                      QuestionStatus.DRAFT,
                      min,
                      max,
                      opts,
                      null,
                      null)),
              List.of(),
              null,
              null);
      pollRepository.insert(draft);
      pollRepository.activateQuestion(pollId, questionId);
      return new P(pollId, questionId, List.copyOf(optionIds));
    }
  }

  @Nested
  class OnPostgres extends CommonArrayBallot {
    @Override
    protected DSLContext dsl() {
      return AbstractPostgresTest.dsl();
    }
  }

  @Nested
  class OnH2 extends CommonArrayBallot {
    private final DataSource ds = AbstractH2Test.freshH2();

    @Override
    protected DSLContext dsl() {
      return AbstractH2Test.dsl(ds);
    }
  }
}
