package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;

/**
 * Storage-level coverage for {@code @TS-004}: concurrent activations on the same poll must leave
 * the DB with exactly one ACTIVE question. The repository uses a single-statement CASE UPDATE so
 * per-statement atomicity (on both PostgreSQL and H2) makes the race race-free; both calls return a
 * Poll, and the final state has one ACTIVE row pointed at by {@code polls.active_question_id}.
 *
 * <p>Ported to {@code @QuarkusTest}: the Postgres branch uses the application's injected
 * {@code @Default} {@link DSLContext} (Dev Services Postgres); the H2 branch builds a
 * self-contained raw-H2 {@link DSLContext} via {@link AbstractH2Test}. {@code @Nested} engine
 * subclasses were dropped because Quarkus cannot CDI-instantiate a {@code @Nested} inner class of a
 * {@code @QuarkusTest}.
 */
@QuarkusTest
class OneActivePerPollIT {

  @Inject DSLContext pgDsl;

  // @TS-004 — Postgres.
  @Test
  void concurrent_activations_on_same_poll_serialise_postgres() throws Exception {
    runConcurrentActivations(pgDsl, "pg");
  }

  // @TS-004 — H2.
  @Test
  void concurrent_activations_on_same_poll_serialise_h2() throws Exception {
    DataSource ds = AbstractH2Test.freshH2();
    runConcurrentActivations(AbstractH2Test.dsl(ds), "h2");
  }

  @Test
  void serial_activation_transitions_question_to_active_postgres() {
    runSerialActivation(pgDsl, "pg");
  }

  @Test
  void serial_activation_transitions_question_to_active_h2() {
    DataSource ds = AbstractH2Test.freshH2();
    runSerialActivation(AbstractH2Test.dsl(ds), "h2");
  }

  @Test
  void reactivating_same_question_is_idempotent_postgres() {
    runReactivateIdempotent(pgDsl, "pg");
  }

  @Test
  void reactivating_same_question_is_idempotent_h2() {
    DataSource ds = AbstractH2Test.freshH2();
    runReactivateIdempotent(AbstractH2Test.dsl(ds), "h2");
  }

  // ---------- shared bodies (run against both engines) --------------------

  private void runConcurrentActivations(DSLContext dsl, String tag) throws Exception {
    PollRepositoryImpl repository = newRepo(dsl, tag);
    Poll seeded = seedPollWithTwoQuestions(repository, tag);
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
      assertThat(successes).as("both atomic UPDATEs commit").isEqualTo(2);

      Poll finalState = repository.findById(seeded.id()).orElseThrow();
      long activeCount =
          finalState.questions().stream().filter(q -> q.status() == QuestionStatus.ACTIVE).count();
      assertThat(activeCount)
          .as("the poll has exactly one ACTIVE question after the race")
          .isEqualTo(1);
      assertThat(finalState.activeQuestionId())
          .as("polls.active_question_id matches the surviving ACTIVE row")
          .isIn(q1, q2);
    }
  }

  private void runSerialActivation(DSLContext dsl, String tag) {
    PollRepositoryImpl repository = newRepo(dsl, tag);
    Poll seeded = seedPollWithTwoQuestions(repository, tag);
    UUID q1 = seeded.questions().getFirst().id();

    Poll after = repository.activateQuestion(seeded.id(), q1);

    Question activated =
        after.questions().stream().filter(q -> q.id().equals(q1)).findFirst().orElseThrow();
    assertThat(activated.status()).isEqualTo(QuestionStatus.ACTIVE);
    assertThat(after.activeQuestionId()).isEqualTo(q1);
    assertThat(after.status()).isEqualTo(PollStatus.OPEN);
  }

  private void runReactivateIdempotent(DSLContext dsl, String tag) {
    PollRepositoryImpl repository = newRepo(dsl, tag);
    Poll seeded = seedPollWithTwoQuestions(repository, tag);
    UUID q1 = seeded.questions().getFirst().id();

    repository.activateQuestion(seeded.id(), q1);
    Poll after = repository.activateQuestion(seeded.id(), q1);

    long activeCount =
        after.questions().stream().filter(q -> q.status() == QuestionStatus.ACTIVE).count();
    assertThat(activeCount).isEqualTo(1);
    assertThat(after.activeQuestionId()).isEqualTo(q1);
  }

  private PollRepositoryImpl newRepo(DSLContext dsl, String tag) {
    PollRepositoryImpl repository = new PollRepositoryImpl(dsl);
    // Each test needs an admin_user row to satisfy the polls.owner_username FK added in V3.
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, ownerFor(tag))
        .set(ADMIN_USER.PASSWORD_HASH, "n/a")
        .set(ADMIN_USER.CREATED_AT, OffsetDateTime.now())
        .onConflictDoNothing()
        .execute();
    return repository;
  }

  private static String ownerFor(String tag) {
    return "concurrency-owner-" + tag;
  }

  private Poll seedPollWithTwoQuestions(PollRepositoryImpl repository, String tag) {
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
            ownerFor(tag),
            "Concurrency test poll",
            "concurrency-" + tag + "-" + pollId.toString().substring(0, 8),
            PollStatus.DRAFT,
            null,
            List.of(
                new Question(
                    q1, pollId, "Q1?", 0, QuestionStatus.DRAFT, 1, 1, q1Options, null, null),
                new Question(
                    q2, pollId, "Q2?", 1, QuestionStatus.DRAFT, 1, 1, q2Options, null, null)),
            List.of(),
            null,
            null);
    return repository.insert(poll);
  }
}
