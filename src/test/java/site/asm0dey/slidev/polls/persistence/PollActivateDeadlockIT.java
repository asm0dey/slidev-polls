package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
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
 * Regression coverage for the slide-switch deadlock storm: when the Slidev addon's mounted
 * PollPanels fire interleaved activate / close POSTs on the same poll while the backoffice issues a
 * PATCH, the original CASE-driven UPDATE in {@code activateQuestion} acquired row locks on {@code
 * poll_questions} in heap order — concurrent activates on different questions of the same poll
 * could grab those locks in different orders and deadlock (PostgreSQL 40P01 / H2 50200).
 *
 * <p>The fix takes a {@code SELECT polls FOR UPDATE} on the owning poll row up front so every
 * activate / close / header-update on the same poll serialises behind one row lock; this test
 * proves the storm completes cleanly with no SQL-level deadlock exception.
 *
 * <p>Parametrised over PostgreSQL and H2 so the same invariant holds on the dev-mode H2 engine.
 */
class PollActivateDeadlockIT extends AbstractPostgresTest {

  private static final int THREADS = 16;
  private static final int ITERATIONS_PER_THREAD = 25;

  abstract class CommonDeadlock {
    protected PollRepositoryImpl repository;

    protected abstract DSLContext dsl();

    @BeforeEach
    void setUp() {
      DSLContext dsl = dsl();
      repository = new PollRepositoryImpl(dsl);
      dsl.insertInto(ADMIN_USER)
          .set(ADMIN_USER.USERNAME, "deadlock-owner")
          .set(ADMIN_USER.PASSWORD_HASH, "n/a")
          .set(ADMIN_USER.CREATED_AT, OffsetDateTime.now())
          .onConflictDoNothing()
          .execute();
    }

    @Test
    void interleaved_activate_and_header_update_does_not_deadlock() throws Exception {
      Poll seeded = seedPollWithFourQuestions();
      List<UUID> qids = seeded.questions().stream().map(Question::id).toList();
      AtomicReference<Throwable> firstFailure = new AtomicReference<>();

      try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(THREADS);
        for (int t = 0; t < THREADS; t++) {
          final int threadIndex = t;
          Callable<Void> work =
              () -> {
                for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                  int op = (threadIndex + i) % 4;
                  try {
                    switch (op) {
                      case 0 -> repository.activateQuestion(seeded.id(), qids.get(i % qids.size()));
                      case 1 -> repository.closeActiveQuestion(seeded.id());
                      case 2 ->
                          repository.updateHeader(
                              seeded.id(), "header-" + threadIndex + "-" + i, seeded.slug());
                      default ->
                          repository.activateQuestion(seeded.id(), qids.get((i + 1) % qids.size()));
                    }
                  } catch (RuntimeException ex) {
                    firstFailure.compareAndSet(null, ex);
                    throw ex;
                  }
                }
                return null;
              };
          futures.add(
              CompletableFuture.runAsync(
                  () -> {
                    try {
                      work.call();
                    } catch (Exception ex) {
                      throw new RuntimeException(ex);
                    }
                  },
                  pool));
        }
        // Wait for every worker; surface the first failure (if any) as the test failure so the
        // assertion below sees a stable post-condition.
        for (CompletableFuture<Void> f : futures) {
          try {
            f.join();
          } catch (RuntimeException ignored) {
            // first failure is captured in firstFailure; keep draining so the executor empties.
          }
        }
      }

      assertThat(firstFailure.get())
          .as("interleaved activate/close/updateHeader storm must not deadlock")
          .isNull();

      // Sanity post-condition: the poll still has at most one ACTIVE question and the row lock
      // serialisation produced a consistent final state.
      Poll finalState = repository.findById(seeded.id()).orElseThrow();
      long activeCount =
          finalState.questions().stream().filter(q -> q.status() == QuestionStatus.ACTIVE).count();
      assertThat(activeCount).isLessThanOrEqualTo(1);
    }

    private Poll seedPollWithFourQuestions() {
      UUID pollId = UUID.randomUUID();
      List<Question> questions = new ArrayList<>(4);
      for (int i = 0; i < 4; i++) {
        UUID qid = UUID.randomUUID();
        List<Option> options =
            List.of(
                new Option(UUID.randomUUID(), qid, "A", 0),
                new Option(UUID.randomUUID(), qid, "B", 1));
        questions.add(
            new Question(qid, pollId, "Q" + i, i, QuestionStatus.DRAFT, 1, 1, options, null, null));
      }
      Poll poll =
          new Poll(
              pollId,
              "deadlock-owner",
              "Deadlock test poll",
              "deadlock-" + pollId.toString().substring(0, 8),
              PollStatus.DRAFT,
              null,
              questions,
              List.of(),
              null,
              null);
      return repository.insert(poll);
    }
  }

  @Nested
  class OnPostgres extends CommonDeadlock {
    @Override
    protected DSLContext dsl() {
      return AbstractPostgresTest.dsl();
    }
  }

  @Nested
  class OnH2 extends CommonDeadlock {
    private final DataSource ds = AbstractH2Test.freshH2();

    @Override
    protected DSLContext dsl() {
      return AbstractH2Test.dsl(ds);
    }
  }
}
