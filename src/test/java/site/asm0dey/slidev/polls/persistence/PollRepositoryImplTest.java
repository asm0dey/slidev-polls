package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;

/**
 * Round-trip persistence coverage for {@link PollRepositoryImpl}, parametrised over PostgreSQL (the
 * application's injected {@code @Default} {@link DSLContext} on Dev Services Postgres) and H2 (a
 * self-contained raw-H2 {@link DSLContext} from {@link AbstractH2Test}).
 *
 * <p>The shared test bodies live on {@link Base}; the {@code @Nested} {@code Postgres}/{@code H2}
 * engine classes supply the {@link DSLContext} and an engine-specific data namespace tag, so each
 * inherited {@code @Test} runs once per engine. The Postgres container is JVM-wide (Dev Services);
 * the {@code pg}/{@code h2} tag and {@code onConflictDoNothing} owner seeding keep rows from
 * colliding across runs, and every H2 nested test gets a brand-new in-memory database.
 */
@QuarkusTest
class PollRepositoryImplTest {

  @Inject DSLContext pgDsl;

  abstract class Base {

    abstract DSLContext dsl();

    abstract String tag();

    private PollRepositoryImpl repoOn(DSLContext dsl) {
      dsl.insertInto(ADMIN_USER)
          .set(ADMIN_USER.USERNAME, ownerFor(tag()))
          .set(ADMIN_USER.PASSWORD_HASH, "n/a")
          .set(ADMIN_USER.CREATED_AT, OffsetDateTime.now())
          .onConflictDoNothing()
          .execute();
      return new PollRepositoryImpl(dsl);
    }

    // @TS-A3-001 --------------------------------------------------------------
    @Test
    void persistsAllowedOriginsRoundTrip() {
      PollRepositoryImpl repo = repoOn(dsl());
      Poll poll =
          buildPoll(
              tag(),
              "round-trip-origins",
              List.of("http://localhost:3030", "https://demo.example.com"));
      Poll inserted = repo.insert(poll);
      Poll loaded = repo.findById(inserted.id()).orElseThrow();
      assertThat(loaded.allowedOrigins())
          .containsExactly("http://localhost:3030", "https://demo.example.com");
    }

    // @TS-A3-002 --------------------------------------------------------------
    @Test
    void updateAllowedOriginsReplacesOrigins() {
      PollRepositoryImpl repo = repoOn(dsl());
      Poll poll = buildPoll(tag(), "update-origins", List.of("http://old.example.com"));
      Poll inserted = repo.insert(poll);

      Poll updated =
          repo.updateAllowedOrigins(
              inserted.id(), List.of("http://new1.example.com", "http://new2.example.com"));
      assertThat(updated.allowedOrigins())
          .containsExactly("http://new1.example.com", "http://new2.example.com");

      Poll reloaded = repo.findById(inserted.id()).orElseThrow();
      assertThat(reloaded.allowedOrigins())
          .containsExactly("http://new1.example.com", "http://new2.example.com");
    }

    // @TS-A3-003 --------------------------------------------------------------
    @Test
    void updateAllowedOriginsClearsWhenEmpty() {
      PollRepositoryImpl repo = repoOn(dsl());
      Poll poll = buildPoll(tag(), "clear-origins", List.of("http://localhost:3030"));
      Poll inserted = repo.insert(poll);
      Poll updated = repo.updateAllowedOrigins(inserted.id(), List.of());
      assertThat(updated.allowedOrigins()).isEmpty();
    }

    // replaceQuestions ID preservation ----------------------------------------
    @Test
    void replaceQuestions_preservesIds() {
      PollRepositoryImpl repo = repoOn(dsl());
      Poll inserted =
          insertPollWithQuestions(
              repo, tag(), "preserve-ids", new QuestionSeed("Q1?", List.of("A", "B")));
      UUID qid = inserted.questions().get(0).id();
      UUID oA = inserted.questions().get(0).options().get(0).id();
      UUID oB = inserted.questions().get(0).options().get(1).id();

      Poll after =
          repo.replaceQuestions(
              inserted.id(),
              List.of(
                  new CreatePollCommand.QuestionUpdate(
                      qid,
                      "Q1 edited?",
                      List.of(
                          new CreatePollCommand.OptionUpdate(oA, "A edited"),
                          new CreatePollCommand.OptionUpdate(oB, "B")))));

      assertThat(after.questions()).hasSize(1);
      assertThat(after.questions().get(0).id()).isEqualTo(qid);
      assertThat(after.questions().get(0).prompt()).isEqualTo("Q1 edited?");
      assertThat(after.questions().get(0).options())
          .extracting(Option::id, Option::label)
          .containsExactly(tuple(oA, "A edited"), tuple(oB, "B"));
    }

    @Test
    void replaceQuestions_assignsFreshIdToNewQuestion() {
      PollRepositoryImpl repo = repoOn(dsl());
      Poll inserted =
          insertPollWithQuestions(repo, tag(), "new-q", new QuestionSeed("Q1?", List.of("A", "B")));
      UUID qid = inserted.questions().get(0).id();
      UUID oA = inserted.questions().get(0).options().get(0).id();
      UUID oB = inserted.questions().get(0).options().get(1).id();

      Poll after =
          repo.replaceQuestions(
              inserted.id(),
              List.of(
                  new CreatePollCommand.QuestionUpdate(
                      qid,
                      "Q1?",
                      List.of(
                          new CreatePollCommand.OptionUpdate(oA, "A"),
                          new CreatePollCommand.OptionUpdate(oB, "B"))),
                  new CreatePollCommand.QuestionUpdate(
                      null,
                      "Q2?",
                      List.of(
                          new CreatePollCommand.OptionUpdate(null, "C"),
                          new CreatePollCommand.OptionUpdate(null, "D")))));

      assertThat(after.questions()).hasSize(2);
      assertThat(after.questions().get(0).id()).isEqualTo(qid);
      assertThat(after.questions().get(1).id()).isNotEqualTo(qid).isNotNull();
      assertThat(after.questions().get(1).options()).extracting(Option::id).doesNotContainNull();
    }

    @Test
    void replaceQuestions_deletesQuestionsMissingFromIncoming() {
      PollRepositoryImpl repo = repoOn(dsl());
      Poll inserted =
          insertPollWithQuestions(
              repo,
              tag(),
              "drop-q",
              new QuestionSeed("keep?", List.of("A", "B")),
              new QuestionSeed("drop?", List.of("C", "D")));
      UUID keep = inserted.questions().get(0).id();
      UUID oA = inserted.questions().get(0).options().get(0).id();
      UUID oB = inserted.questions().get(0).options().get(1).id();

      Poll after =
          repo.replaceQuestions(
              inserted.id(),
              List.of(
                  new CreatePollCommand.QuestionUpdate(
                      keep,
                      "keep?",
                      List.of(
                          new CreatePollCommand.OptionUpdate(oA, "A"),
                          new CreatePollCommand.OptionUpdate(oB, "B")))));

      assertThat(after.questions()).hasSize(1);
      assertThat(after.questions().get(0).id()).isEqualTo(keep);
    }

    @Test
    void replaceQuestions_reorderingPreservesIdsAndUpdatesOrdinal() {
      PollRepositoryImpl repo = repoOn(dsl());
      Poll inserted =
          insertPollWithQuestions(
              repo,
              tag(),
              "reorder",
              new QuestionSeed("first?", List.of("A", "B")),
              new QuestionSeed("second?", List.of("C", "D")));
      UUID q0 = inserted.questions().get(0).id();
      UUID q1 = inserted.questions().get(1).id();
      UUID o0A = inserted.questions().get(0).options().get(0).id();
      UUID o0B = inserted.questions().get(0).options().get(1).id();
      UUID o1C = inserted.questions().get(1).options().get(0).id();
      UUID o1D = inserted.questions().get(1).options().get(1).id();

      Poll after =
          repo.replaceQuestions(
              inserted.id(),
              List.of(
                  new CreatePollCommand.QuestionUpdate(
                      q1,
                      "second?",
                      List.of(
                          new CreatePollCommand.OptionUpdate(o1C, "C"),
                          new CreatePollCommand.OptionUpdate(o1D, "D"))),
                  new CreatePollCommand.QuestionUpdate(
                      q0,
                      "first?",
                      List.of(
                          new CreatePollCommand.OptionUpdate(o0A, "A"),
                          new CreatePollCommand.OptionUpdate(o0B, "B")))));

      assertThat(after.questions()).extracting(Question::id).containsExactly(q1, q0);
      assertThat(after.questions().get(0).ordinal()).isZero();
      assertThat(after.questions().get(1).ordinal()).isEqualTo(1);
    }

    // delete cascade ----------------------------------------------------------
    @Test
    void deletePollCascadesQuestionsWithoutTriggerError() {
      PollRepositoryImpl repo = repoOn(dsl());
      Poll inserted =
          insertPollWithQuestions(
              repo, tag(), "cascade-delete", new QuestionSeed("Q?", List.of("A", "B")));
      repo.delete(inserted.id());
      assertThat(repo.findById(inserted.id())).isEmpty();
    }
  }

  @Nested
  class Postgres extends Base {
    @Override
    DSLContext dsl() {
      return pgDsl;
    }

    @Override
    String tag() {
      return "pg";
    }
  }

  @Nested
  class H2 extends Base {
    @Override
    DSLContext dsl() {
      return AbstractH2Test.dsl(AbstractH2Test.freshH2());
    }

    @Override
    String tag() {
      return "h2";
    }
  }

  // ---------- shared helpers / fixtures --------------------------------------

  private static String ownerFor(String tag) {
    return "repo-test-owner-" + tag;
  }

  private record QuestionSeed(String prompt, List<String> optionLabels) {}

  private Poll insertPollWithQuestions(
      PollRepositoryImpl repo, String tag, String slugSuffix, QuestionSeed... seeds) {
    UUID pollId = UUID.randomUUID();
    List<Question> questions = new ArrayList<>(seeds.length);
    for (int i = 0; i < seeds.length; i++) {
      UUID qid = UUID.randomUUID();
      List<Option> options = new ArrayList<>(seeds[i].optionLabels().size());
      for (int j = 0; j < seeds[i].optionLabels().size(); j++) {
        options.add(new Option(UUID.randomUUID(), qid, seeds[i].optionLabels().get(j), j));
      }
      questions.add(
          new Question(
              qid, pollId, seeds[i].prompt(), i, QuestionStatus.DRAFT, 1, 1, options, null, null));
    }
    Poll poll =
        new Poll(
            pollId,
            ownerFor(tag),
            "Test poll",
            slugSuffix + "-" + tag + "-" + pollId.toString().substring(0, 8),
            PollStatus.DRAFT,
            null,
            questions,
            List.of(),
            null,
            null);
    return repo.insert(poll);
  }

  private Poll buildPoll(String tag, String slugSuffix, List<String> allowedOrigins) {
    UUID pollId = UUID.randomUUID();
    UUID q1 = UUID.randomUUID();
    List<Option> options =
        new ArrayList<>(
            List.of(
                new Option(UUID.randomUUID(), q1, "A", 0),
                new Option(UUID.randomUUID(), q1, "B", 1)));
    return new Poll(
        pollId,
        ownerFor(tag),
        "Test poll",
        slugSuffix + "-" + tag + "-" + pollId.toString().substring(0, 8),
        PollStatus.DRAFT,
        null,
        List.of(new Question(q1, pollId, "Q?", 0, QuestionStatus.DRAFT, 1, 1, options, null, null)),
        allowedOrigins,
        null,
        null);
  }
}
