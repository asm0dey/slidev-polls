package site.asm0dey.slidev.polls.core.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.error.ResourceHasVotesException;

/**
 * Unit coverage for the FR-013 RESOURCE_HAS_VOTES lock at {@link PollService#updateForOwner}: once
 * a question has recorded votes, deleting it, deleting any of its options, or changing its arity is
 * rejected; reword-only edits are still allowed.
 */
class PollServiceLockTest {

  @Test
  void deletingOptionWithVotesRejected() {
    Fixture f = pollWithVotedOption();
    assertThatThrownBy(() -> f.service.updateForOwner(f.pollId, "alice", f.removeFirstOption()))
        .isInstanceOf(ResourceHasVotesException.class);
  }

  @Test
  void deletingQuestionWithVotesRejected() {
    Fixture f = pollWithVotedOption();
    assertThatThrownBy(() -> f.service.updateForOwner(f.pollId, "alice", f.removeActiveQuestion()))
        .isInstanceOf(ResourceHasVotesException.class);
  }

  @Test
  void changingArityWithVotesRejected() {
    Fixture f = pollWithVotedOption();
    assertThatThrownBy(
            () -> f.service.updateForOwner(f.pollId, "alice", f.flipArityOnActiveQuestion(0, 3)))
        .isInstanceOf(ResourceHasVotesException.class);
  }

  @Test
  void editingPromptOrOptionLabelAllowed() {
    Fixture f = pollWithVotedOption();
    assertThatCode(() -> f.service.updateForOwner(f.pollId, "alice", f.rewordPromptAndLabels()))
        .doesNotThrowAnyException();
  }

  // --- fixture wiring --------------------------------------------------------------------------

  private Fixture pollWithVotedOption() {
    FakeRepo repo = new FakeRepo();
    FakeVotes votes = new FakeVotes();
    PollService[] holder = new PollService[1];
    ObjectProvider<PollService> provider =
        new ObjectProvider<>() {
          @Override
          public PollService getObject() {
            return holder[0];
          }
        };
    PollService service = new PollService(repo, ev -> {}, provider, votes);
    holder[0] = service;

    Poll created =
        service.create(
            "alice",
            new CreatePollCommand(
                "Vote-locked poll",
                "vote-locked",
                List.of(
                    new CreatePollCommand.QuestionDraft(
                        "Which one?",
                        List.of(
                            new CreatePollCommand.OptionDraft("A"),
                            new CreatePollCommand.OptionDraft("B")))),
                null));
    UUID qid = created.questions().get(0).id();
    UUID oid = created.questions().get(0).options().get(0).id();
    votes.insert(
        new Vote(UUID.randomUUID(), created.id(), qid, List.of(oid), "voter-1", Instant.now()));
    // Mirror the vote into the poll-side count cache; the production
    // PollRepositoryImpl computes this via SQL against the votes table.
    repo.voteCounts.put(qid, 1L);
    return new Fixture(service, repo, created);
  }

  /**
   * Test fixture helper: surfaces the pre-built poll/service plus payload-building shortcuts that
   * mirror the user-facing edit gestures described in the plan.
   */
  static final class Fixture {
    final PollService service;
    final FakeRepo repo;
    final UUID pollId;
    final Poll poll;

    Fixture(PollService service, FakeRepo repo, Poll poll) {
      this.service = service;
      this.repo = repo;
      this.pollId = poll.id();
      this.poll = poll;
    }

    UpdatePollCommand removeFirstOption() {
      Question q = poll.questions().get(0);
      // Drop the first option (the voted one). The two remaining option slots are required for
      // QuestionUpdate's @Size(min=2), so insert a new option to keep arity valid.
      List<CreatePollCommand.OptionUpdate> opts = new ArrayList<>();
      opts.add(
          new CreatePollCommand.OptionUpdate(q.options().get(1).id(), q.options().get(1).label()));
      opts.add(new CreatePollCommand.OptionUpdate(null, "C"));
      return new UpdatePollCommand(
          null,
          null,
          List.of(new CreatePollCommand.QuestionUpdate(q.id(), q.prompt(), 1, 1, opts)),
          null);
    }

    UpdatePollCommand removeActiveQuestion() {
      // Drop every question from the update payload — the (originally only) voted question is now
      // absent, so the service must reject the call.
      return new UpdatePollCommand(null, null, List.of(), null);
    }

    UpdatePollCommand flipArityOnActiveQuestion(int min, int max) {
      Question q = poll.questions().get(0);
      List<CreatePollCommand.OptionUpdate> opts = new ArrayList<>();
      for (Option o : q.options()) {
        opts.add(new CreatePollCommand.OptionUpdate(o.id(), o.label()));
      }
      return new UpdatePollCommand(
          null,
          null,
          List.of(new CreatePollCommand.QuestionUpdate(q.id(), q.prompt(), min, max, opts)),
          null);
    }

    UpdatePollCommand rewordPromptAndLabels() {
      Question q = poll.questions().get(0);
      List<CreatePollCommand.OptionUpdate> opts = new ArrayList<>();
      for (Option o : q.options()) {
        opts.add(new CreatePollCommand.OptionUpdate(o.id(), o.label() + " (renamed)"));
      }
      return new UpdatePollCommand(
          null,
          null,
          List.of(
              new CreatePollCommand.QuestionUpdate(q.id(), q.prompt() + " (renamed)", 1, 1, opts)),
          null);
    }
  }

  // --- fakes -----------------------------------------------------------------------------------

  static final class FakeRepo implements PollRepository {
    private final Map<UUID, Poll> byId = new HashMap<>();
    Map<UUID, Long> voteCounts = new HashMap<>();

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
      Poll existing = require(pollId);
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
      Poll existing = require(pollId);
      Map<UUID, Question> existingByQid = new HashMap<>();
      for (Question q : existing.questions()) existingByQid.put(q.id(), q);
      List<Question> rebuilt = new ArrayList<>(incoming.size());
      for (int i = 0; i < incoming.size(); i++) {
        CreatePollCommand.QuestionUpdate qu = incoming.get(i);
        UUID qid =
            (qu.id() != null && existingByQid.containsKey(qu.id())) ? qu.id() : UUID.randomUUID();
        Map<UUID, Option> existingOpts = new HashMap<>();
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
                qid,
                pollId,
                qu.prompt(),
                i,
                QuestionStatus.DRAFT,
                qu.minSelections(),
                qu.maxSelections(),
                opts,
                null,
                null));
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
      throw new UnsupportedOperationException();
    }

    @Override
    public Poll closeActiveQuestion(UUID pollId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Poll updateAllowedOrigins(UUID pollId, List<String> origins) {
      Poll existing = require(pollId);
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
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<UUID, Long> voteCountByQuestion(UUID pollId) {
      return Map.copyOf(voteCounts);
    }

    private Poll require(UUID pollId) {
      Poll existing = byId.get(pollId);
      if (existing == null) {
        throw new NotFoundException(pollId.toString());
      }
      return existing;
    }
  }

  static final class FakeVotes implements VoteRepository {
    final List<Vote> rows = new ArrayList<>();
    final Map<UUID, Long> perQuestion = new HashMap<>();

    @Override
    public Vote insert(Vote vote) {
      rows.add(vote);
      perQuestion.merge(vote.questionId(), 1L, Long::sum);
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
    public Optional<List<UUID>> deleteByQuestionAndVoter(UUID questionId, String voterToken) {
      throw new UnsupportedOperationException();
    }
  }
}
