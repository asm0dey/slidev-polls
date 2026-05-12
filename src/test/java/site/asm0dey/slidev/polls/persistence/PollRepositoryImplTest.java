package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests for {@link PollRepositoryImpl} against a real Postgres instance
 * (Testcontainers). Covers round-trip persistence of poll fields beyond what the
 * concurrency-focused ITs cover.
 */
class PollRepositoryImplTest extends AbstractPostgresTest {

  private PollRepositoryImpl repo;

  @BeforeEach
  void setUp() {
    DSLContext dsl = dsl();
    repo = new PollRepositoryImpl(dsl, JsonMapper.builder().build());
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, "repo-test-owner")
        .set(ADMIN_USER.PASSWORD_HASH, "n/a")
        .set(ADMIN_USER.CREATED_AT, OffsetDateTime.now())
        .onConflictDoNothing()
        .execute();
  }

  // @TS-A3-001 — allowed_origins written on insert are returned unchanged on findById.
  @Test
  void persistsAllowedOriginsRoundTrip() {
    Poll poll =
        buildPoll(
            "round-trip-origins", List.of("http://localhost:3030", "https://demo.example.com"));
    Poll inserted = repo.insert(poll);
    Poll loaded = repo.findById(inserted.id()).orElseThrow();
    assertThat(loaded.allowedOrigins())
        .containsExactly("http://localhost:3030", "https://demo.example.com");
  }

  // @TS-A3-002 — updateAllowedOrigins replaces the origins list and returns the updated poll.
  @Test
  void updateAllowedOriginsReplacesOrigins() {
    Poll poll = buildPoll("update-origins", List.of("http://old.example.com"));
    Poll inserted = repo.insert(poll);

    Poll updated =
        repo.updateAllowedOrigins(
            inserted.id(), List.of("http://new1.example.com", "http://new2.example.com"));
    assertThat(updated.allowedOrigins())
        .containsExactly("http://new1.example.com", "http://new2.example.com");

    // Confirm persistence via a fresh load.
    Poll reloaded = repo.findById(inserted.id()).orElseThrow();
    assertThat(reloaded.allowedOrigins())
        .containsExactly("http://new1.example.com", "http://new2.example.com");
  }

  // @TS-A3-003 — updateAllowedOrigins with an empty list clears all origins.
  @Test
  void updateAllowedOriginsClearsWhenEmpty() {
    Poll poll = buildPoll("clear-origins", List.of("http://localhost:3030"));
    Poll inserted = repo.insert(poll);

    Poll updated = repo.updateAllowedOrigins(inserted.id(), List.of());
    assertThat(updated.allowedOrigins()).isEmpty();
  }

  // Task 3 — ID preservation across replaceQuestions
  @Test
  void replaceQuestions_preservesIdsForMatchedQuestionsAndOptions() {
    Poll inserted =
        insertPollWithQuestions("preserve-ids", new QuestionSeed("Q1?", List.of("A", "B")));
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
    Poll inserted = insertPollWithQuestions("new-q", new QuestionSeed("Q1?", List.of("A", "B")));
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
    Poll inserted =
        insertPollWithQuestions(
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
    Poll inserted =
        insertPollWithQuestions(
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

  private record QuestionSeed(String prompt, List<String> optionLabels) {}

  private Poll insertPollWithQuestions(String slugSuffix, QuestionSeed... seeds) {
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
              qid, pollId, seeds[i].prompt(), i, QuestionStatus.DRAFT, options, null, null));
    }
    Poll poll =
        new Poll(
            pollId,
            "repo-test-owner",
            "Test poll",
            slugSuffix + "-" + pollId.toString().substring(0, 8),
            PollStatus.DRAFT,
            Map.of(),
            null,
            questions,
            List.of(),
            null,
            null);
    return repo.insert(poll);
  }

  private static Poll buildPoll(String slugSuffix, List<String> allowedOrigins) {
    UUID pollId = UUID.randomUUID();
    UUID q1 = UUID.randomUUID();
    List<Option> options =
        new ArrayList<>(
            List.of(
                new Option(UUID.randomUUID(), q1, "A", 0),
                new Option(UUID.randomUUID(), q1, "B", 1)));
    return new Poll(
        pollId,
        "repo-test-owner",
        "Test poll",
        slugSuffix + "-" + pollId.toString().substring(0, 8),
        PollStatus.DRAFT,
        Map.of(),
        null,
        List.of(new Question(q1, pollId, "Q?", 0, QuestionStatus.DRAFT, options, null, null)),
        allowedOrigins,
        null,
        null);
  }
}
