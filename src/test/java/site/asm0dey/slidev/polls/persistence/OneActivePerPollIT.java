package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
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

/**
 * Storage-level coverage for {@code @TS-004}: the partial unique index {@code
 * poll_questions_one_active_uq ON poll_questions(poll_id) WHERE status = 'ACTIVE'} must serialise
 * concurrent activations. Two threads race to mark different DRAFT questions ACTIVE on the same
 * poll; exactly one succeeds, the other's UPDATE surfaces as a {@link
 * site.asm0dey.slidev.polls.persistence.PollRepositoryImpl.ConcurrentActivationException}.
 *
 * <p>Parametrised over PostgreSQL and H2 via nested {@code @Nested} engine subclasses.
 */
class OneActivePerPollIT extends AbstractPostgresTest {

  abstract class CommonOneActive {
    protected PollRepositoryImpl repository;

    protected abstract DSLContext dsl();

    @BeforeEach
    void setUp() {
      DSLContext dsl = dsl();
      repository = new PollRepositoryImpl(dsl);
      // Each test needs an admin_user row to satisfy the polls.owner_username FK added in V3.
      dsl.insertInto(ADMIN_USER)
          .set(ADMIN_USER.USERNAME, "concurrency-owner")
          .set(ADMIN_USER.PASSWORD_HASH, "n/a")
          .set(ADMIN_USER.CREATED_AT, OffsetDateTime.now())
          .onConflictDoNothing()
          .execute();
    }

    // @TS-004 — exactly one of two concurrent ACTIVE transitions on the same poll wins; the loser
    // sees the unique-constraint violation translated into ConcurrentActivationException.
    @Test
    void concurrent_activations_on_same_poll_serialise() throws Exception {
      Poll seeded = seedPollWithTwoQuestions();
      UUID q1 = seeded.questions().get(0).id();
      UUID q2 = seeded.questions().get(1).id();

      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        Callable<Object> activateQ1 =
            () -> {
              try {
                return repository.activateQuestion(seeded.id(), q1);
              } catch (RuntimeException e) {
                return e;
              }
            };
        Callable<Object> activateQ2 =
            () -> {
              try {
                return repository.activateQuestion(seeded.id(), q2);
              } catch (RuntimeException e) {
                return e;
              }
            };

        Future<Object> f1 = pool.submit(activateQ1);
        Future<Object> f2 = pool.submit(activateQ2);

        Object r1 = f1.get();
        Object r2 = f2.get();

        long successes = Stream.of(r1, r2).filter(Poll.class::isInstance).count();
        long failures =
            Stream.of(r1, r2)
                .filter(r -> r instanceof PollRepositoryImpl.ConcurrentActivationException)
                .count();

        assertThat(successes).as("exactly one activation wins under concurrent start").isEqualTo(1);
        assertThat(failures).as("the other hits the partial unique index").isEqualTo(1);

        Poll finalState = repository.findById(seeded.id()).orElseThrow();
        long activeCount =
            finalState.questions().stream()
                .filter(q -> q.status() == QuestionStatus.ACTIVE)
                .count();
        assertThat(activeCount)
            .as("the poll still has at most one ACTIVE question after the race")
            .isEqualTo(1);
        assertThat(finalState.activeQuestionId()).isNotNull();
      }
    }

    // Positive-path companion: a clean serial activation succeeds end-to-end, giving the
    // concurrent test a meaningful contrast — proves the repository's atomic update isn't broken
    // in the happy path either.
    @Test
    void serial_activation_transitions_question_to_active() {
      Poll seeded = seedPollWithTwoQuestions();
      UUID q1 = seeded.questions().getFirst().id();

      Poll after = repository.activateQuestion(seeded.id(), q1);

      Question activated =
          after.questions().stream().filter(q -> q.id().equals(q1)).findFirst().orElseThrow();
      assertThat(activated.status()).isEqualTo(QuestionStatus.ACTIVE);
      assertThat(after.activeQuestionId()).isEqualTo(q1);
      assertThat(after.status()).isEqualTo(PollStatus.OPEN);
    }

    // Guard: activating an already-ACTIVE question is a no-op (@TS-052). The partial unique index
    // would otherwise refuse the second UPDATE on the same row.
    @Test
    void reactivating_same_question_is_idempotent() {
      Poll seeded = seedPollWithTwoQuestions();
      UUID q1 = seeded.questions().getFirst().id();

      repository.activateQuestion(seeded.id(), q1);
      Poll after = repository.activateQuestion(seeded.id(), q1);

      long activeCount =
          after.questions().stream().filter(q -> q.status() == QuestionStatus.ACTIVE).count();
      assertThat(activeCount).isEqualTo(1);
      assertThat(after.activeQuestionId()).isEqualTo(q1);
    }

    private Poll seedPollWithTwoQuestions() {
      UUID pollId = UUID.randomUUID();
      UUID q1 = UUID.randomUUID();
      UUID q2 = UUID.randomUUID();
      List<Option> q1Options =
          new ArrayList<>(
              List.of(
                  new Option(UUID.randomUUID(), q1, "A", 0),
                  new Option(UUID.randomUUID(), q1, "B", 1)));
      List<Option> q2Options =
          new ArrayList<>(
              List.of(
                  new Option(UUID.randomUUID(), q2, "C", 0),
                  new Option(UUID.randomUUID(), q2, "D", 1)));

      Poll poll =
          new Poll(
              pollId,
              "concurrency-owner",
              "Concurrency test poll",
              "concurrency-" + pollId.toString().substring(0, 8),
              PollStatus.DRAFT,
              null,
              List.of(
                  new Question(q1, pollId, "Q1?", 0, QuestionStatus.DRAFT, q1Options, null, null),
                  new Question(q2, pollId, "Q2?", 1, QuestionStatus.DRAFT, q2Options, null, null)),
              List.of(), // allowedOrigins
              null,
              null);
      return repository.insert(poll);
    }
  }

  @Nested
  class OnPostgres extends CommonOneActive {
    @Override
    protected DSLContext dsl() {
      return AbstractPostgresTest.dsl();
    }
  }

  @Nested
  class OnH2 extends CommonOneActive {
    private final DataSource ds = AbstractH2Test.freshH2();

    @Override
    protected DSLContext dsl() {
      return AbstractH2Test.dsl(ds);
    }
  }
}
