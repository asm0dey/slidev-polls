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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;

/**
 * Regression coverage for the slide-switch deadlock storm: interleaved activate / close POSTs and a
 * PATCH on the same poll. The R1 fallback removed the explicit {@code dsl.transactionResult} from
 * {@code activateQuestion}/{@code closeActiveQuestion}; the single-statement CASE UPDATEs run on
 * the ambient transaction and per-statement atomicity keeps the storm consistent.
 *
 * <p>Ported to {@code @QuarkusTest}: Postgres uses the application's injected {@code @Default}
 * {@link DSLContext} (Dev Services Postgres); H2 uses a self-contained raw-H2 {@link DSLContext}.
 * The {@code @Nested} engine subclasses were flattened into per-engine test methods.
 */
@QuarkusTest
class PollActivateDeadlockIT {

  private static final int THREADS = 16;
  private static final int ITERATIONS_PER_THREAD = 25;

  @Inject DSLContext pgDsl;

  @Test
  void interleaved_activate_and_header_update_does_not_deadlock_postgres() throws Exception {
    seedOwner(pgDsl);
    runStorm(pgDsl);
  }

  @Test
  void interleaved_activate_and_header_update_does_not_deadlock_h2() throws Exception {
    DataSource ds = AbstractH2Test.freshH2();
    DSLContext dsl = AbstractH2Test.dsl(ds);
    seedOwner(dsl);
    runStorm(dsl);
  }

  private static void seedOwner(DSLContext dsl) {
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, "deadlock-owner")
        .set(ADMIN_USER.PASSWORD_HASH, "n/a")
        .set(ADMIN_USER.CREATED_AT, OffsetDateTime.now())
        .onConflictDoNothing()
        .execute();
  }

  private void runStorm(DSLContext dsl) throws Exception {
    PollRepositoryImpl repository = new PollRepositoryImpl(dsl);
    Poll seeded = seedPollWithFourQuestions(repository);
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
                final int it = i;
                try {
                  // In production these repository calls run inside PollService's @Transactional
                  // boundary, so lockPollRow's SELECT ... FOR UPDATE holds the owning poll-row lock
                  // for the whole unit of work and serialises the per-question CASE UPDATE. Calling
                  // the repository directly on a bare DSLContext would autocommit each statement
                  // and
                  // release the lock immediately — defeating the serialisation and re-introducing
                  // the
                  // very deadlock this test guards against. Wrap each op in a transaction to mirror
                  // the production boundary.
                  dsl.transaction(
                      cfg -> {
                        DSLContext tx = cfg.dsl();
                        PollRepositoryImpl txRepo = new PollRepositoryImpl(tx);
                        switch (op) {
                          case 0 ->
                              txRepo.activateQuestion(seeded.id(), qids.get(it % qids.size()));
                          case 1 -> txRepo.closeActiveQuestion(seeded.id());
                          case 2 ->
                              txRepo.updateHeader(
                                  seeded.id(), "header-" + threadIndex + "-" + it, seeded.slug());
                          default ->
                              txRepo.activateQuestion(
                                  seeded.id(), qids.get((it + 1) % qids.size()));
                        }
                      });
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

    // Sanity post-condition: the poll still has at most one ACTIVE question and the
    // serialisation produced a consistent final state.
    Poll finalState = repository.findById(seeded.id()).orElseThrow();
    long activeCount =
        finalState.questions().stream().filter(q -> q.status() == QuestionStatus.ACTIVE).count();
    assertThat(activeCount).isLessThanOrEqualTo(1);
  }

  private Poll seedPollWithFourQuestions(PollRepositoryImpl repository) {
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
