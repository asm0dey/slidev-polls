package site.asm0dey.slidev.polls.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.error.ActivationRejectedException;
import site.asm0dey.slidev.polls.core.error.InvalidOriginException;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.error.NotOwnerException;
import site.asm0dey.slidev.polls.core.error.SlugInvalidException;
import site.asm0dey.slidev.polls.core.error.SlugReservedException;
import site.asm0dey.slidev.polls.core.error.SlugTakenException;

/**
 * Pure-Java unit coverage for {@link PollService}. A {@link FakePollRepository} stands in for the
 * jOOQ-backed {@code PollRepositoryImpl} so every branch of slug derivation, ownership enforcement,
 * activation precondition, and the ≥2-options gate can be exercised without Spring or a live
 * Postgres. The Gherkin scenarios enumerated in the method docs are the assertion anchors per
 * Principle VII.
 */
class PollServiceTest {

  private FakePollRepository repository;
  private PollService service;

  @BeforeEach
  void setUp() {
    repository = new FakePollRepository();
    org.springframework.beans.factory.ObjectProvider<PollService> selfProvider =
        new org.springframework.beans.factory.ObjectProvider<>() {
          @Override
          public PollService getObject() {
            return service;
          }
        };
    service = new PollService(repository, new RecordingEventPublisher(), selfProvider);
  }

  // @TS-010 — when the presenter does not supply a slug, the server derives one from the title
  // using kebab-case (spaces → `-`, lowercased).
  @Test
  void derives_slug_from_title_when_none_supplied() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand(
                "Quickstart demo",
                null,
                null,
                List.of(questionDraft("Which JVM?", "OpenJDK", "GraalVM")),
                null));

    assertThat(created.slug()).isEqualTo("quickstart-demo");
    assertThat(created.ownerUsername()).isEqualTo("alice");
    assertThat(created.status()).isEqualTo(PollStatus.DRAFT);
  }

  // @TS-011 — slug format is validated on create. A malformed candidate surfaces as
  // SlugInvalidException, never SlugTakenException/SlugReservedException.
  @Test
  void rejects_invalid_slug_format() {
    assertThatThrownBy(
            () ->
                service.create(
                    "alice",
                    new CreatePollCommand(
                        "irrelevant",
                        "UPPER",
                        null,
                        List.of(questionDraft("Prompt?", "A", "B")),
                        null)))
        .isInstanceOf(SlugInvalidException.class);
  }

  // @TS-012 — reserved slugs are rejected with a distinct error code, surfaced at the service
  // level as SlugReservedException. `admin` is on ReservedSlugs.
  @Test
  void rejects_reserved_slug() {
    assertThatThrownBy(
            () ->
                service.create(
                    "alice",
                    new CreatePollCommand(
                        "irrelevant",
                        "admin",
                        null,
                        List.of(questionDraft("Prompt?", "A", "B")),
                        null)))
        .isInstanceOf(SlugReservedException.class);
  }

  // @TS-013 / @TS-014 — slug collision on create surfaces as SlugTakenException even if the case
  // differs (the repository's slugTaken implementation is case-insensitive).
  @Test
  void rejects_slug_collision() {
    service.create(
        "alice",
        new CreatePollCommand(
            "My Talk", "my-talk", null, List.of(questionDraft("Prompt?", "A", "B")), null));

    assertThatThrownBy(
            () ->
                service.create(
                    "alice",
                    new CreatePollCommand(
                        "another",
                        "my-talk",
                        null,
                        List.of(questionDraft("Prompt?", "A", "B")),
                        null)))
        .isInstanceOf(SlugTakenException.class);
  }

  // @TS-040 / @TS-041 — ownership is enforced: a non-owner attempting to read, update, or delete
  // a poll gets NotOwnerException (surfaced to the API layer as FORBIDDEN).
  @Test
  void non_owner_cannot_read_or_mutate_poll() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand(
                "my poll", "my-poll", null, List.of(questionDraft("Q?", "A", "B")), null));

    assertThatThrownBy(() -> service.getForOwner(created.id(), "bob"))
        .isInstanceOf(NotOwnerException.class);
    assertThatThrownBy(() -> service.deleteForOwner(created.id(), "bob"))
        .isInstanceOf(NotOwnerException.class);
  }

  // @TS-003 — presenter-visible activation: delegates to the repository's atomic activate. Having
  // previously activated Q1, activating Q2 closes Q1 and marks Q2 ACTIVE.
  @Test
  void activating_second_question_closes_the_first() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand(
                "agenda",
                "agenda",
                null,
                List.of(questionDraft("Q1?", "A", "B"), questionDraft("Q2?", "C", "D")),
                null));
    UUID q1 = created.questions().get(0).id();
    UUID q2 = created.questions().get(1).id();

    service.activateQuestionForOwner(created.id(), "alice", q1);
    Poll afterSecond = service.activateQuestionForOwner(created.id(), "alice", q2);

    Question q1After = findQuestion(afterSecond, q1);
    Question q2After = findQuestion(afterSecond, q2);
    assertThat(q1After.status()).isEqualTo(QuestionStatus.CLOSED);
    assertThat(q2After.status()).isEqualTo(QuestionStatus.ACTIVE);
    assertThat(afterSecond.activeQuestionId()).isEqualTo(q2);
  }

  // @TS-004 precursor — a question with fewer than two options cannot be activated. The partial-
  // unique-index concurrency path is exercised by the persistence test (T042); here we pin the
  // service-layer pre-check.
  @Test
  void refuses_to_activate_question_with_fewer_than_two_options() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand(
                "broken", "broken", null, List.of(questionDraft("Q?", "only-one")), null));
    UUID qid = created.questions().get(0).id();

    assertThatThrownBy(() -> service.activateQuestionForOwner(created.id(), "alice", qid))
        .isInstanceOf(ActivationRejectedException.class);
  }

  // @TS-006 — deletes are gated on ownership and on the poll existing.
  @Test
  void delete_removes_the_poll_for_owner() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand(
                "disposable", "disposable", null, List.of(questionDraft("Q?", "A", "B")), null));

    service.deleteForOwner(created.id(), "alice");

    assertThatThrownBy(() -> service.getForOwner(created.id(), "alice"))
        .isInstanceOf(NotFoundException.class);
  }

  // @TS-015 — renaming the slug on an existing poll runs the validation chain (format, reserved,
  // taken) against the new value but skips the poll being renamed from the taken check.
  @Test
  void rename_slug_updates_header_without_self_collision() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand(
                "talk", "old-slug", null, List.of(questionDraft("Q?", "A", "B")), null));

    Poll renamed =
        service.updateForOwner(
            created.id(), "alice", new UpdatePollCommand(null, "new-slug", null, null));

    assertThat(renamed.slug()).isEqualTo("new-slug");
  }

  // Regression — ownership check fires before any repository mutation, so a non-owner activate
  // cannot mutate state.
  @Test
  void non_owner_cannot_activate() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand(
                "x", "x-slug", null, List.of(questionDraft("Q?", "A", "B")), null));
    UUID qid = created.questions().get(0).id();

    assertThatThrownBy(() -> service.activateQuestionForOwner(created.id(), "bob", qid))
        .isInstanceOf(NotOwnerException.class);
  }

  // @TS-A4a — a malformed origin (e.g. containing a space) is rejected with InvalidOriginException
  // whose message echoes the offending value.
  @Test
  void rejectsMalformedOrigin() {
    assertThatThrownBy(
            () ->
                service.create(
                    "alice",
                    new CreatePollCommand(
                        "bad-origin-poll",
                        "bad-origin-poll",
                        null,
                        List.of(questionDraft("Q?", "A", "B")),
                        List.of("not a url"))))
        .isInstanceOf(InvalidOriginException.class)
        .hasMessageContaining("not a url");
  }

  // @TS-A4b — a valid origin is accepted and persisted verbatim after normalisation.
  @Test
  void acceptsValidOriginsOnCreate() {
    Poll p =
        service.create(
            "alice",
            new CreatePollCommand(
                "good-origin-poll",
                "good-origin-poll",
                null,
                List.of(questionDraft("Q?", "A", "B")),
                List.of("http://localhost:3030")));
    assertThat(p.allowedOrigins()).containsExactly("http://localhost:3030");
  }

  // @TS-A4c — scheme and host are lower-cased; explicit port is preserved; trailing slash stripped.
  @Test
  void normalisesOrigins() {
    Poll p =
        service.create(
            "alice",
            new CreatePollCommand(
                "normalise-poll",
                "normalise-poll",
                null,
                List.of(questionDraft("Q?", "A", "B")),
                List.of("HTTP://Demo.example.COM:443/")));
    // host lowercased, default port preserved (explicit in input), trailing slash removed
    assertThat(p.allowedOrigins()).containsExactly("http://demo.example.com:443");
  }

  private static CreatePollCommand.QuestionDraft questionDraft(String prompt, String... options) {
    List<CreatePollCommand.OptionDraft> opts = new ArrayList<>();
    for (String o : options) {
      opts.add(new CreatePollCommand.OptionDraft(o));
    }
    return new CreatePollCommand.QuestionDraft(prompt, opts);
  }

  private static Question findQuestion(Poll poll, UUID id) {
    return poll.questions().stream().filter(q -> q.id().equals(id)).findFirst().orElseThrow();
  }

  /**
   * Minimal in-memory {@link PollRepository} sufficient to exercise {@link PollService}. Maintains
   * slug-taken semantics case-insensitively to mirror what {@code PollRepositoryImpl} does via
   * {@code lower(slug)}; does not attempt concurrency — that is T042's remit.
   */
  static final class FakePollRepository implements PollRepository {

    private final Map<UUID, Poll> byId = new HashMap<>();

    @Override
    public Poll insert(Poll poll) {
      byId.put(poll.id(), poll);
      return poll;
    }

    @Override
    public Optional<Poll> findById(UUID pollId) {
      return Optional.ofNullable(byId.get(pollId));
    }

    @Override
    public Optional<Poll> findBySlug(String slug) {
      return byId.values().stream().filter(p -> p.slug().equalsIgnoreCase(slug)).findFirst();
    }

    @Override
    public List<Poll> findByOwner(String ownerUsername) {
      return byId.values().stream().filter(p -> p.ownerUsername().equals(ownerUsername)).toList();
    }

    @Override
    public boolean slugTaken(String slug, UUID excludingPollId) {
      return byId.values().stream()
          .anyMatch(
              p ->
                  p.slug().equalsIgnoreCase(slug)
                      && (excludingPollId == null || !p.id().equals(excludingPollId)));
    }

    @Override
    public Poll updateHeader(UUID pollId, String title, String slug) {
      Poll existing = requirePresent(pollId);
      Poll updated =
          new Poll(
              existing.id(),
              existing.ownerUsername(),
              title,
              slug,
              existing.status(),
              existing.style(),
              existing.activeQuestionId(),
              existing.questions(),
              existing.allowedOrigins(),
              existing.createdAt(),
              Instant.now());
      byId.put(pollId, updated);
      return updated;
    }

    @Override
    public Poll replaceQuestions(UUID pollId, List<CreatePollCommand.QuestionDraft> questions) {
      Poll existing = requirePresent(pollId);
      List<Question> rebuilt = new ArrayList<>(questions.size());
      for (int i = 0; i < questions.size(); i++) {
        CreatePollCommand.QuestionDraft d = questions.get(i);
        UUID qid = UUID.randomUUID();
        List<Option> opts = new ArrayList<>(d.options().size());
        for (int j = 0; j < d.options().size(); j++) {
          opts.add(new Option(UUID.randomUUID(), qid, d.options().get(j).label(), j));
        }
        rebuilt.add(
            new Question(qid, pollId, d.prompt(), i, QuestionStatus.DRAFT, opts, null, null));
      }
      Poll updated =
          new Poll(
              existing.id(),
              existing.ownerUsername(),
              existing.title(),
              existing.slug(),
              PollStatus.DRAFT,
              existing.style(),
              null,
              rebuilt,
              existing.allowedOrigins(),
              existing.createdAt(),
              Instant.now());
      byId.put(pollId, updated);
      return updated;
    }

    @Override
    public Poll updateStyle(UUID pollId, Map<String, Object> style) {
      Poll existing = requirePresent(pollId);
      Poll updated =
          new Poll(
              existing.id(),
              existing.ownerUsername(),
              existing.title(),
              existing.slug(),
              existing.status(),
              style,
              existing.activeQuestionId(),
              existing.questions(),
              existing.allowedOrigins(),
              existing.createdAt(),
              Instant.now());
      byId.put(pollId, updated);
      return updated;
    }

    @Override
    public void delete(UUID pollId) {
      if (byId.remove(pollId) == null) {
        throw new NotFoundException(pollId.toString());
      }
    }

    @Override
    public Poll activateQuestion(UUID pollId, UUID questionId) {
      Poll existing = requirePresent(pollId);
      List<Question> updated = new ArrayList<>(existing.questions().size());
      for (Question q : existing.questions()) {
        if (q.id().equals(questionId)) {
          updated.add(
              new Question(
                  q.id(),
                  q.pollId(),
                  q.prompt(),
                  q.ordinal(),
                  QuestionStatus.ACTIVE,
                  q.options(),
                  Instant.now(),
                  null));
        } else if (q.status() == QuestionStatus.ACTIVE) {
          updated.add(
              new Question(
                  q.id(),
                  q.pollId(),
                  q.prompt(),
                  q.ordinal(),
                  QuestionStatus.CLOSED,
                  q.options(),
                  q.activatedAt(),
                  Instant.now()));
        } else {
          updated.add(q);
        }
      }
      Poll after =
          new Poll(
              existing.id(),
              existing.ownerUsername(),
              existing.title(),
              existing.slug(),
              PollStatus.OPEN,
              existing.style(),
              questionId,
              updated,
              existing.allowedOrigins(),
              existing.createdAt(),
              Instant.now());
      byId.put(pollId, after);
      return after;
    }

    @Override
    public Poll closeActiveQuestion(UUID pollId) {
      Poll existing = requirePresent(pollId);
      List<Question> updated = new ArrayList<>(existing.questions().size());
      for (Question q : existing.questions()) {
        if (q.status() == QuestionStatus.ACTIVE) {
          updated.add(
              new Question(
                  q.id(),
                  q.pollId(),
                  q.prompt(),
                  q.ordinal(),
                  QuestionStatus.CLOSED,
                  q.options(),
                  q.activatedAt(),
                  Instant.now()));
        } else {
          updated.add(q);
        }
      }
      Poll after =
          new Poll(
              existing.id(),
              existing.ownerUsername(),
              existing.title(),
              existing.slug(),
              existing.status(),
              existing.style(),
              null,
              updated,
              existing.allowedOrigins(),
              existing.createdAt(),
              Instant.now());
      byId.put(pollId, after);
      return after;
    }

    @Override
    public Poll updateAllowedOrigins(UUID pollId, List<String> origins) {
      Poll existing = requirePresent(pollId);
      Poll updated =
          new Poll(
              existing.id(),
              existing.ownerUsername(),
              existing.title(),
              existing.slug(),
              existing.status(),
              existing.style(),
              existing.activeQuestionId(),
              existing.questions(),
              List.copyOf(origins),
              existing.createdAt(),
              Instant.now());
      byId.put(pollId, updated);
      return updated;
    }

    @Override
    public boolean isOriginAllowedByAnyPoll(String origin) {
      return false;
    }

    private Poll requirePresent(UUID pollId) {
      Poll existing = byId.get(pollId);
      if (existing == null) {
        throw new NotFoundException(pollId.toString());
      }
      return existing;
    }
  }

  /** No-op event sink — individual tests that care about events can subclass and inspect. */
  static final class RecordingEventPublisher implements ApplicationEventPublisher {

    @Override
    public void publishEvent(Object event) {}

    @Override
    public void publishEvent(ApplicationEvent event) {}
  }
}
