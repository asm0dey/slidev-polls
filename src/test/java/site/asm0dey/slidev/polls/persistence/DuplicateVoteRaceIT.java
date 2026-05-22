package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.VOTES;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jooq.DSLContext;
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
 * Storage-level coverage for {@code @TS-024}: the unique index {@code votes_question_voter_uq ON
 * votes (question_id, voter_token)} must serialise concurrent duplicate submissions. Two threads
 * race to insert a vote on the same question for the same voter_token; exactly one row lands, the
 * other's insert surfaces as {@link AlreadyVotedException}.
 *
 * <p>Postgres uses the application's injected {@code @Default} {@link DSLContext} (the JVM-wide Dev
 * Services Postgres); H2 uses a fresh self-contained raw-H2 {@link DSLContext} per test. The shared
 * test body lives on {@link Base} and runs once per {@code @Nested} engine. The repository maps
 * jOOQ's own {@link org.jooq.exception.IntegrityConstraintViolationException} (no Spring
 * translator) to {@link AlreadyVotedException}.
 */
@QuarkusTest
class DuplicateVoteRaceIT {

  @Inject DSLContext pgDsl;

  abstract class Base {

    abstract DSLContext dsl();

    // @TS-024 — two submissions with the same voter_token arrive concurrently; exactly one lands.
    @Test
    void concurrent_duplicate_submissions_yield_exactly_one_recorded_vote() throws Exception {
      DSLContext dsl = dsl();
      seedOwner(dsl);
      runRace(dsl);
    }
  }

  @Nested
  class Postgres extends Base {
    @Override
    DSLContext dsl() {
      return pgDsl;
    }
  }

  @Nested
  class H2 extends Base {
    @Override
    DSLContext dsl() {
      return AbstractH2Test.dsl(AbstractH2Test.freshH2());
    }
  }

  private static void seedOwner(DSLContext dsl) {
    // polls.owner_username FK is added in V3; seed the owner row once per test.
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, "race-owner")
        .set(ADMIN_USER.PASSWORD_HASH, "n/a")
        .set(ADMIN_USER.CREATED_AT, OffsetDateTime.now())
        .onConflictDoNothing()
        .execute();
  }

  private void runRace(DSLContext dsl) throws Exception {
    PollRepositoryImpl pollRepository = new PollRepositoryImpl(dsl);
    VoteRepositoryImpl voteRepository = new VoteRepositoryImpl(dsl);
    Poll seeded = seedActivatedPoll(pollRepository);
    UUID questionId = seeded.activeQuestionId();
    UUID optionA = seeded.questions().getFirst().options().getFirst().id();

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Callable<Object> submit =
          () -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            try {
              return voteRepository.insert(
                  new Vote(
                      UUID.randomUUID(),
                      seeded.id(),
                      questionId,
                      java.util.List.of(optionA),
                      "v-123",
                      Instant.now()));
            } catch (RuntimeException e) {
              return e;
            }
          };
      Future<Object> f1 = pool.submit(submit);
      Future<Object> f2 = pool.submit(submit);
      // Line the threads up at the same starting gate to maximise the chance of a true race.
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();

      Object r1 = f1.get();
      Object r2 = f2.get();

      long successes = Stream.of(r1, r2).filter(Vote.class::isInstance).count();
      long conflicts = Stream.of(r1, r2).filter(AlreadyVotedException.class::isInstance).count();

      assertThat(successes)
          .as("exactly one submission wins the (question_id, voter_token) index")
          .isEqualTo(1);
      assertThat(conflicts).as("the other sees the unique-constraint violation").isEqualTo(1);

      long rows =
          dsl.fetchCount(
              dsl.selectFrom(VOTES)
                  .where(VOTES.QUESTION_ID.eq(questionId).and(VOTES.VOTER_TOKEN.eq("v-123"))));
      assertThat(rows)
          .as("votes has a single row for (question_id, v-123) after the race")
          .isEqualTo(1L);
    }
  }

  private Poll seedActivatedPoll(PollRepositoryImpl pollRepository) {
    UUID pollId = UUID.randomUUID();
    UUID q1 = UUID.randomUUID();
    List<Option> q1Options =
        new ArrayList<>(
            List.of(
                new Option(UUID.randomUUID(), q1, "A", 0),
                new Option(UUID.randomUUID(), q1, "B", 1)));
    Poll draft =
        new Poll(
            pollId,
            "race-owner",
            "Race poll",
            "race-" + pollId.toString().substring(0, 8),
            PollStatus.DRAFT,
            null,
            List.of(
                new Question(
                    q1, pollId, "Q1?", 0, QuestionStatus.DRAFT, 1, 1, q1Options, null, null)),
            List.of(), // allowedOrigins
            null,
            null);
    pollRepository.insert(draft);
    // Activate the question via the repository so the partial index and the activated_at
    // timestamp constraint both agree with the row shape VoteRepositoryImpl observes.
    return pollRepository.activateQuestion(pollId, q1);
  }
}
