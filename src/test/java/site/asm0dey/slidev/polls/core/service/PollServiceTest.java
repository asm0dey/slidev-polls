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
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.error.ActivationRejectedException;
import site.asm0dey.slidev.polls.core.error.AlreadyVotedException;
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
  private FakeVoteRepository fakeVoteRepository;
  private PollService service;

  @BeforeEach
  void setUp() {
    repository = new FakePollRepository();
    fakeVoteRepository = new FakeVoteRepository();
    org.springframework.beans.factory.ObjectProvider<PollService> selfProvider =
        new org.springframework.beans.factory.ObjectProvider<>() {
          @Override
          public PollService getObject() {
            return service;
          }
        };
    service =
        new PollService(
            repository, new RecordingEventPublisher(), selfProvider, fakeVoteRepository);
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
                        "irrelevant", "UPPER", List.of(questionDraft("Prompt?", "A", "B")), null)))
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
                        "irrelevant", "admin", List.of(questionDraft("Prompt?", "A", "B")), null)))
        .isInstanceOf(SlugReservedException.class);
  }

  // @TS-013 / @TS-014 — slug collision on create surfaces as SlugTakenException even if the case
  // differs (the repository's slugTaken implementation is case-insensitive).
  @Test
  void rejects_slug_collision() {
    service.create(
        "alice",
        new CreatePollCommand(
            "My Talk", "my-talk", List.of(questionDraft("Prompt?", "A", "B")), null));

    assertThatThrownBy(
            () ->
                service.create(
                    "alice",
                    new CreatePollCommand(
                        "another", "my-talk", List.of(questionDraft("Prompt?", "A", "B")), null)))
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
                "my poll", "my-poll", List.of(questionDraft("Q?", "A", "B")), null));

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
                "broken", "broken", List.of(questionDraft("Q?", "only-one")), null));
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
                "disposable", "disposable", List.of(questionDraft("Q?", "A", "B")), null));

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
                "talk", "old-slug", List.of(questionDraft("Q?", "A", "B")), null));

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
            new CreatePollCommand("x", "x-slug", List.of(questionDraft("Q?", "A", "B")), null));
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
                List.of(questionDraft("Q?", "A", "B")),
                List.of("HTTP://Demo.example.COM:443/")));
    // host lowercased, default port preserved (explicit in input), trailing slash removed
    assertThat(p.allowedOrigins()).containsExactly("http://demo.example.com:443");
  }

  @Test
  void updateForOwner_preservesQuestionAndOptionIds() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand("t", "preserve", List.of(questionDraft("Q?", "A", "B")), null));
    UUID qid = created.questions().get(0).id();
    UUID oA = created.questions().get(0).options().get(0).id();
    UUID oB = created.questions().get(0).options().get(1).id();

    Poll after =
        service.updateForOwner(
            created.id(),
            "alice",
            new UpdatePollCommand(
                null,
                null,
                List.of(
                    new CreatePollCommand.QuestionUpdate(
                        qid,
                        "Q renamed?",
                        List.of(
                            new CreatePollCommand.OptionUpdate(oA, "A2"),
                            new CreatePollCommand.OptionUpdate(oB, "B")))),
                null));

    assertThat(after.questions().get(0).id()).isEqualTo(qid);
    assertThat(after.questions().get(0).options()).extracting(Option::id).containsExactly(oA, oB);
    assertThat(after.questions().get(0).prompt()).isEqualTo("Q renamed?");
  }

  @Test
  void cloneForOwner_copiesContentWithFreshIdsAndDerivedSlug() {
    Poll src =
        service.create(
            "alice",
            new CreatePollCommand(
                "My Talk",
                "my-talk",
                List.of(questionDraft("Q?", "A", "B")),
                List.of("https://example.com")));

    Poll clone = service.cloneForOwner(src.id(), "alice");

    assertThat(clone.id()).isNotEqualTo(src.id());
    assertThat(clone.title()).isEqualTo("Copy of My Talk");
    assertThat(clone.slug()).isNotEqualTo(src.slug());
    assertThat(clone.questions()).hasSize(1);
    assertThat(clone.questions().get(0).id()).isNotEqualTo(src.questions().get(0).id());
    assertThat(clone.questions().get(0).options())
        .extracting(Option::label)
        .containsExactly("A", "B");
    assertThat(clone.allowedOrigins()).containsExactly("https://example.com");
  }

  @Test
  void cloneForOwner_rejectsNonOwner() {
    Poll src =
        service.create(
            "alice",
            new CreatePollCommand(
                "talk", "talk-abc", List.of(questionDraft("Q?", "A", "B")), null));
    assertThatThrownBy(() -> service.cloneForOwner(src.id(), "bob"))
        .isInstanceOf(NotOwnerException.class);
  }

  @Test
  void clearVotesForOwner_deletesVotesAndResetsQuestionsToDraft() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand(
                "t", "clear-votes", List.of(questionDraft("Q?", "A", "B")), null));
    UUID qid = created.questions().get(0).id();
    UUID oid = created.questions().get(0).options().get(0).id();
    service.activateQuestionForOwner(created.id(), "alice", qid);

    fakeVoteRepository.insert(
        new Vote(UUID.randomUUID(), created.id(), qid, List.of(oid), "voter-1", Instant.now()));

    Poll after = service.clearVotesForOwner(created.id(), "alice");

    assertThat(after.activeQuestionId()).isNull();
    assertThat(after.status()).isEqualTo(PollStatus.DRAFT);
    assertThat(after.questions().get(0).id()).isEqualTo(qid);
    assertThat(after.questions().get(0).status()).isEqualTo(QuestionStatus.DRAFT);
    assertThat(fakeVoteRepository.tally(qid)).isEmpty();
  }

  @Test
  void clearVotesForOwner_rejectsNonOwner() {
    Poll p =
        service.create(
            "alice",
            new CreatePollCommand(
                "t", "clear-not-owner", List.of(questionDraft("Q?", "A", "B")), null));
    assertThatThrownBy(() -> service.clearVotesForOwner(p.id(), "bob"))
        .isInstanceOf(NotOwnerException.class);
  }

  @Test
  void closeActiveQuestion_closesActiveAndEmitsEvent() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand("t", "deck-close", List.of(questionDraft("Q?", "A", "B")), null));
    UUID qid = created.questions().get(0).id();
    service.activateQuestionForOwner(created.id(), "alice", qid);

    Poll after = service.closeActiveQuestion(created.id());

    assertThat(after.activeQuestionId()).isNull();
    assertThat(findQuestion(after, qid).status()).isEqualTo(QuestionStatus.CLOSED);
  }

  @Test
  void closeActiveQuestion_isIdempotentWhenNothingActive() {
    Poll created =
        service.create(
            "alice",
            new CreatePollCommand(
                "t", "deck-close-idem", List.of(questionDraft("Q?", "A", "B")), null));

    Poll after = service.closeActiveQuestion(created.id());

    assertThat(after.activeQuestionId()).isNull();
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
              existing.activeQuestionId(),
              existing.questions(),
              existing.allowedOrigins(),
              existing.createdAt(),
              Instant.now());
      byId.put(pollId, updated);
      return updated;
    }

    @Override
    public Poll replaceQuestions(UUID pollId, List<CreatePollCommand.QuestionUpdate> incoming) {
      Poll existing = requirePresent(pollId);
      java.util.Map<UUID, Question> existingByQid = new java.util.HashMap<>();
      for (Question q : existing.questions()) existingByQid.put(q.id(), q);
      List<Question> rebuilt = new ArrayList<>(incoming.size());
      for (int i = 0; i < incoming.size(); i++) {
        CreatePollCommand.QuestionUpdate qu = incoming.get(i);
        UUID qid =
            (qu.id() != null && existingByQid.containsKey(qu.id())) ? qu.id() : UUID.randomUUID();
        java.util.Map<UUID, Option> existingOpts = new java.util.HashMap<>();
        if (existingByQid.containsKey(qid)) {
          for (Option o : existingByQid.get(qid).options()) existingOpts.put(o.id(), o);
        }
        List<Option> opts = new ArrayList<>();
        for (int j = 0; j < qu.options().size(); j++) {
          CreatePollCommand.OptionUpdate ou = qu.options().get(j);
          UUID oid =
              (ou.id() != null && existingOpts.containsKey(ou.id())) ? ou.id() : UUID.randomUUID();
          opts.add(new Option(oid, qid, ou.label(), j));
        }
        rebuilt.add(
            new Question(
                qid, pollId, qu.prompt(), i, QuestionStatus.DRAFT, 1, 1, opts, null, null));
      }
      Poll updated =
          new Poll(
              existing.id(),
              existing.ownerUsername(),
              existing.title(),
              existing.slug(),
              PollStatus.DRAFT,
              null,
              rebuilt,
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
                  q.minSelections(),
                  q.maxSelections(),
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
                  q.minSelections(),
                  q.maxSelections(),
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
                  q.minSelections(),
                  q.maxSelections(),
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

    @Override
    public Poll resetQuestionsToDraft(UUID pollId) {
      Poll existing = requirePresent(pollId);
      List<Question> updated = new ArrayList<>(existing.questions().size());
      for (Question q : existing.questions()) {
        updated.add(
            new Question(
                q.id(),
                q.pollId(),
                q.prompt(),
                q.ordinal(),
                QuestionStatus.DRAFT,
                q.minSelections(),
                q.maxSelections(),
                q.options(),
                null,
                null));
      }
      Poll after =
          new Poll(
              existing.id(),
              existing.ownerUsername(),
              existing.title(),
              existing.slug(),
              PollStatus.DRAFT,
              null,
              updated,
              existing.allowedOrigins(),
              existing.createdAt(),
              Instant.now());
      byId.put(pollId, after);
      return after;
    }

    private Poll requirePresent(UUID pollId) {
      Poll existing = byId.get(pollId);
      if (existing == null) {
        throw new NotFoundException(pollId.toString());
      }
      return existing;
    }

    @Override
    public java.util.Map<UUID, Long> voteCountByQuestion(UUID pollId) {
      return java.util.Map.of();
    }
  }

  /**
   * Minimal in-memory {@link VoteRepository} sufficient to exercise {@link PollService}'s
   * clearVotesForOwner path.
   */
  static final class FakeVoteRepository implements VoteRepository {
    private final List<Vote> rows = new ArrayList<>();

    @Override
    public Vote insert(Vote vote) {
      boolean duplicate =
          rows.stream()
              .anyMatch(
                  r ->
                      r.questionId().equals(vote.questionId())
                          && r.voterToken().equals(vote.voterToken()));
      if (duplicate) {
        throw new AlreadyVotedException("vote already recorded for question " + vote.questionId());
      }
      rows.add(vote);
      return vote;
    }

    @Override
    public boolean alreadyVoted(UUID questionId, String voterToken) {
      return rows.stream()
          .anyMatch(r -> r.questionId().equals(questionId) && r.voterToken().equals(voterToken));
    }

    @Override
    public Map<UUID, Long> tally(UUID questionId) {
      Map<UUID, Long> out = new HashMap<>();
      for (Vote v : rows) {
        if (v.questionId().equals(questionId)) {
          for (UUID oid : v.optionIds()) {
            out.merge(oid, 1L, Long::sum);
          }
        }
      }
      return out;
    }

    @Override
    public long voterCount(UUID questionId) {
      return rows.stream().filter(v -> v.questionId().equals(questionId)).count();
    }

    @Override
    public int deleteForPoll(UUID pollId) {
      int before = rows.size();
      rows.removeIf(v -> v.pollId().equals(pollId));
      return before - rows.size();
    }

    @Override
    public java.util.Optional<List<UUID>> deleteByQuestionAndVoter(
        UUID questionId, String voterToken) {
      throw new UnsupportedOperationException("not needed for PollServiceTest");
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
