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
import site.asm0dey.slidev.polls.core.error.AlreadyVotedException;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.error.QuestionNotActiveException;
import site.asm0dey.slidev.polls.core.event.VoteCastEvent;

/**
 * Pure-Java unit coverage for {@link VoteService}. In-file fakes for {@link PollRepository} and
 * {@link VoteRepository} let every branch of the voting path — missing active question, option not
 * in question, duplicate voter, concurrent close, event publication — be exercised without Spring
 * or a live Postgres. The Gherkin scenarios enumerated in the method docs are the assertion anchors
 * per Principle VII.
 */
class VoteServiceTest {

  private FakePollRepository polls;
  private FakeVoteRepository votes;
  private RecordingEventPublisher events;
  private VoteService service;

  @BeforeEach
  void setUp() {
    polls = new FakePollRepository();
    votes = new FakeVoteRepository();
    events = new RecordingEventPublisher();
    service = new VoteService(polls, votes, events);
  }

  // @TS-022 — happy path: a valid submission against the ACTIVE question is accepted; a row lands
  // in votes and a VoteCastEvent fans out with the post-write tally for the chosen option.
  @Test
  void records_vote_against_active_question_and_publishes_event() {
    Poll seeded = seedPollWithActiveQuestion();
    UUID optionA = seeded.questions().get(0).options().get(0).id();

    Vote stored = service.recordVote(seeded.slug(), List.of(optionA), "v-123");

    assertThat(stored.optionIds()).containsExactly(optionA);
    assertThat(stored.voterToken()).isEqualTo("v-123");
    assertThat(stored.questionId()).isEqualTo(seeded.activeQuestionId());
    assertThat(votes.rowsFor(seeded.activeQuestionId())).hasSize(1);
    assertThat(events.published())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e).isInstanceOf(VoteCastEvent.class);
              VoteCastEvent ev = (VoteCastEvent) e;
              assertThat(ev.pollId()).isEqualTo(seeded.id());
              assertThat(ev.questionId()).isEqualTo(seeded.activeQuestionId());
            });
  }

  // @TS-021 — the poll exists but has no active question: surface QUESTION_NOT_ACTIVE without
  // attempting a wasted insert. The early-exit keeps the repo out of the hot path for the
  // trivially-rejected case.
  @Test
  void rejects_vote_when_poll_has_no_active_question() {
    Poll seeded = seedPollWithoutActiveQuestion();
    UUID anyOption = seeded.questions().get(0).options().get(0).id();

    assertThatThrownBy(() -> service.recordVote(seeded.slug(), List.of(anyOption), "v-999"))
        .isInstanceOf(QuestionNotActiveException.class);
    assertThat(votes.allRows()).isEmpty();
    assertThat(events.published()).isEmpty();
  }

  // Retract path mirror of the @TS-021 reject — caller invokes retract while the poll has no
  // active question, the service early-exits with QuestionNotActiveException before touching
  // the repository, and no event is published.
  @Test
  void rejects_retract_when_poll_has_no_active_question() {
    Poll seeded = seedPollWithoutActiveQuestion();

    assertThatThrownBy(() -> service.retractVote(seeded.slug(), "v-1"))
        .isInstanceOf(QuestionNotActiveException.class);
    assertThat(events.published()).isEmpty();
  }

  @Test
  void retract_deletes_row_and_publishes_event_with_decremented_tally() {
    Poll seeded = seedPollWithActiveQuestion();
    UUID optionA = seeded.questions().get(0).options().get(0).id();
    service.recordVote(seeded.slug(), List.of(optionA), "v-1");

    service.retractVote(seeded.slug(), "v-1");

    assertThat(votes.rowsFor(seeded.activeQuestionId())).isEmpty();
    assertThat(events.published()).hasSize(2);
    Object second = events.published().get(1);
    assertThat(second).isInstanceOf(site.asm0dey.slidev.polls.core.event.VoteRetractedEvent.class);
    var retracted = (site.asm0dey.slidev.polls.core.event.VoteRetractedEvent) second;
    assertThat(retracted.pollId()).isEqualTo(seeded.id());
    assertThat(retracted.questionId()).isEqualTo(seeded.activeQuestionId());
  }

  @Test
  void retract_with_no_row_is_silent_no_op() {
    Poll seeded = seedPollWithActiveQuestion();

    service.retractVote(seeded.slug(), "v-never-voted");

    assertThat(votes.allRows()).isEmpty();
    assertThat(events.published()).isEmpty();
  }

  @Test
  void retract_propagates_question_not_active_when_question_closes_mid_flight() {
    Poll seeded = seedPollWithActiveQuestion();
    UUID optionA = seeded.questions().get(0).options().get(0).id();
    service.recordVote(seeded.slug(), List.of(optionA), "v-1");
    votes.simulateConcurrentClose(seeded.activeQuestionId());

    assertThatThrownBy(() -> service.retractVote(seeded.slug(), "v-1"))
        .isInstanceOf(QuestionNotActiveException.class);
    // Row stays — the DELETE was refused by the status guard.
    assertThat(votes.rowsFor(seeded.activeQuestionId())).hasSize(1);
    // Only the original VoteCastEvent landed; no retract event.
    assertThat(events.published()).hasSize(1);
  }

  // A submitted optionId that does not belong to the currently-active question is a 404-shaped
  // failure: the caller referenced an option that is not on the board. Validates that the service
  // does not accept cross-question option IDs (e.g., an option from the prior, now-CLOSED,
  // question).
  @Test
  void rejects_option_from_a_different_question() {
    Poll seeded = seedPollWithActiveQuestion();
    // Options of the non-active question are not valid submissions.
    UUID otherQuestionOption = seeded.questions().get(1).options().get(0).id();

    assertThatThrownBy(() -> service.recordVote(seeded.slug(), List.of(otherQuestionOption), "v-1"))
        .isInstanceOf(NotFoundException.class);
    assertThat(votes.allRows()).isEmpty();
    assertThat(events.published()).isEmpty();
  }

  // @TS-023 — duplicate submission from the same voter_token is refused by the repo's unique
  // index; the service must translate the storage-level failure into the canonical Problem.
  @Test
  void rejects_duplicate_vote_for_same_voter_token() {
    Poll seeded = seedPollWithActiveQuestion();
    UUID optionA = seeded.questions().get(0).options().get(0).id();

    service.recordVote(seeded.slug(), List.of(optionA), "v-dup");

    assertThatThrownBy(() -> service.recordVote(seeded.slug(), List.of(optionA), "v-dup"))
        .isInstanceOf(AlreadyVotedException.class);
    // Only the first insert landed; the second never reached storage.
    assertThat(votes.rowsFor(seeded.activeQuestionId())).hasSize(1);
    assertThat(events.published()).hasSize(1);
  }

  // @TS-025 — vote arriving after the active question was just closed (races a close) must
  // surface QUESTION_NOT_ACTIVE from the repo-level INSERT ... SELECT guard. The service must
  // propagate that, not swallow it.
  @Test
  void rejects_vote_when_question_closes_mid_flight() {
    Poll seeded = seedPollWithActiveQuestion();
    UUID optionA = seeded.questions().get(0).options().get(0).id();
    // Simulate a concurrent ACTIVE -> CLOSED transition by flipping the fake repo's flag; the
    // real VoteRepositoryImpl sees this via the INSERT ... SELECT returning zero rows.
    votes.simulateConcurrentClose(seeded.activeQuestionId());

    assertThatThrownBy(() -> service.recordVote(seeded.slug(), List.of(optionA), "v-late"))
        .isInstanceOf(QuestionNotActiveException.class);
    assertThat(votes.allRows()).isEmpty();
    assertThat(events.published()).isEmpty();
  }

  // alreadyVoted is a read-only helper used to populate PublicPollView.alreadyVoted. The service
  // guards against nulls and empty tokens because the cookie may be missing on first visit.
  @Test
  void already_voted_reports_cookie_state() {
    Poll seeded = seedPollWithActiveQuestion();
    UUID optionA = seeded.questions().get(0).options().get(0).id();
    service.recordVote(seeded.slug(), List.of(optionA), "v-seen");

    assertThat(service.alreadyVoted(seeded.activeQuestionId(), "v-seen")).isTrue();
    assertThat(service.alreadyVoted(seeded.activeQuestionId(), "v-new")).isFalse();
    assertThat(service.alreadyVoted(seeded.activeQuestionId(), null)).isFalse();
    assertThat(service.alreadyVoted(seeded.activeQuestionId(), "")).isFalse();
    assertThat(service.alreadyVoted(null, "v-seen")).isFalse();
  }

  // ---------- fixtures -------------------------------------------------------

  private Poll seedPollWithActiveQuestion() {
    UUID pollId = UUID.randomUUID();
    UUID q1 = UUID.randomUUID();
    UUID q2 = UUID.randomUUID();
    List<Option> q1Options =
        List.of(
            new Option(UUID.randomUUID(), q1, "A", 0), new Option(UUID.randomUUID(), q1, "B", 1));
    List<Option> q2Options =
        List.of(
            new Option(UUID.randomUUID(), q2, "C", 0), new Option(UUID.randomUUID(), q2, "D", 1));
    Poll poll =
        new Poll(
            pollId,
            "alice",
            "My talk",
            "my-talk",
            PollStatus.OPEN,
            q1,
            List.of(
                new Question(
                    q1,
                    pollId,
                    "Q1",
                    0,
                    QuestionStatus.ACTIVE,
                    1,
                    1,
                    q1Options,
                    Instant.now(),
                    null),
                new Question(
                    q2, pollId, "Q2", 1, QuestionStatus.DRAFT, 1, 1, q2Options, null, null)),
            List.of(), // allowedOrigins
            Instant.now(),
            Instant.now());
    polls.insert(poll);
    return poll;
  }

  private Poll seedPollWithoutActiveQuestion() {
    UUID pollId = UUID.randomUUID();
    UUID q1 = UUID.randomUUID();
    List<Option> q1Options =
        List.of(
            new Option(UUID.randomUUID(), q1, "A", 0), new Option(UUID.randomUUID(), q1, "B", 1));
    Poll poll =
        new Poll(
            pollId,
            "alice",
            "Waiting",
            "waiting-poll",
            PollStatus.DRAFT,
            null,
            List.of(
                new Question(
                    q1, pollId, "Q1", 0, QuestionStatus.DRAFT, 1, 1, q1Options, null, null)),
            List.of(), // allowedOrigins
            Instant.now(),
            Instant.now());
    polls.insert(poll);
    return poll;
  }

  // ---------- fakes ----------------------------------------------------------

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
      return List.copyOf(byId.values());
    }

    @Override
    public Poll transferOwner(UUID pollId, String newOwnerUsername) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Poll> findOwnedOrCollaborated(String username) {
      return List.copyOf(byId.values());
    }

    @Override
    public boolean slugTaken(String slug, UUID excludingPollId) {
      return false;
    }

    @Override
    public Poll updateHeader(UUID pollId, String title, String slug) {
      throw new UnsupportedOperationException("not needed for VoteServiceTest");
    }

    @Override
    public Poll replaceQuestions(UUID pollId, List<CreatePollCommand.QuestionUpdate> questions) {
      throw new UnsupportedOperationException("not needed for VoteServiceTest");
    }

    @Override
    public void delete(UUID pollId) {
      throw new UnsupportedOperationException("not needed for VoteServiceTest");
    }

    @Override
    public Poll activateQuestion(UUID pollId, UUID questionId) {
      throw new UnsupportedOperationException("not needed for VoteServiceTest");
    }

    @Override
    public Poll closeActiveQuestion(UUID pollId) {
      throw new UnsupportedOperationException("not needed for VoteServiceTest");
    }

    @Override
    public Poll updateAllowedOrigins(UUID pollId, java.util.List<String> origins) {
      throw new UnsupportedOperationException("not needed for VoteServiceTest");
    }

    @Override
    public boolean isOriginAllowedByAnyPoll(String origin) {
      return false;
    }

    @Override
    public Poll resetQuestionsToDraft(UUID pollId) {
      throw new UnsupportedOperationException("not needed for VoteServiceTest");
    }

    @Override
    public java.util.Map<UUID, Long> voteCountByQuestion(UUID pollId) {
      return java.util.Map.of();
    }
  }

  /**
   * Minimal fake. Indexes rows by {@code (questionId, voterToken)} to mimic the unique constraint
   * and supports {@link #simulateConcurrentClose(UUID)} as the test-only analogue of the {@code
   * INSERT ... SELECT WHERE status = 'ACTIVE'} zero-rows-inserted branch.
   */
  static final class FakeVoteRepository implements VoteRepository {
    private final List<Vote> rows = new ArrayList<>();
    private final java.util.Set<UUID> closedQuestions = new java.util.HashSet<>();

    @Override
    public Vote insert(Vote vote) {
      if (closedQuestions.contains(vote.questionId())) {
        throw new QuestionNotActiveException("question " + vote.questionId() + " is not ACTIVE");
      }
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

    List<Vote> rowsFor(UUID questionId) {
      return rows.stream().filter(r -> r.questionId().equals(questionId)).toList();
    }

    List<Vote> allRows() {
      return List.copyOf(rows);
    }

    void simulateConcurrentClose(UUID questionId) {
      closedQuestions.add(questionId);
    }

    @Override
    public int deleteForPoll(UUID pollId) {
      throw new UnsupportedOperationException("not needed for VoteServiceTest");
    }

    @Override
    public java.util.Optional<List<UUID>> deleteByQuestionAndVoter(
        UUID questionId, String voterToken) {
      if (closedQuestions.contains(questionId)) {
        throw new QuestionNotActiveException("question " + questionId + " is not ACTIVE");
      }
      var match =
          rows.stream()
              .filter(r -> r.questionId().equals(questionId) && r.voterToken().equals(voterToken))
              .findFirst();
      match.ifPresent(rows::remove);
      return match.map(Vote::optionIds);
    }
  }

  static final class RecordingEventPublisher implements ApplicationEventPublisher {
    private final List<Object> received = new ArrayList<>();

    @Override
    public void publishEvent(Object event) {
      received.add(event);
    }

    @Override
    public void publishEvent(ApplicationEvent event) {
      received.add(event);
    }

    List<Object> published() {
      return List.copyOf(received);
    }
  }
}
