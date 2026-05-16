package site.asm0dey.slidev.polls.realtime.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.event.PollActiveQuestionChangedEvent;
import site.asm0dey.slidev.polls.core.event.PollQuestionClosedEvent;
import site.asm0dey.slidev.polls.core.event.VoteCastEvent;
import site.asm0dey.slidev.polls.core.event.VoteRetractedEvent;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import site.asm0dey.slidev.polls.core.service.VoteRepository;
import site.asm0dey.slidev.polls.realtime.SseHub;

/**
 * Covers the SSE broadcast contract:
 *
 * <ul>
 *   <li>{@code @TS-030} — snapshot on connect / fresh snapshot on every vote
 *   <li>{@code @TS-031} — fresh snapshot on active-question change
 *   <li>{@code question-closed} on presenter-driven close
 * </ul>
 *
 * <p>Every ballot change re-broadcasts a full {@link SnapshotPayload}; the legacy delta-shaped
 * {@code tally} event has been removed. Pure-Java fakes; no Spring. The {@link SseHub} is a real
 * instance (its concurrency is pinned by {@code SseHubConcurrencyTest}); everything else is
 * in-memory.
 */
class TallyBroadcastTest {

  private InMemoryPollRepository polls;
  private InMemoryVoteRepository votes;
  private SseHub hub;
  private SnapshotBuilder builder;
  private TallyBroadcaster broadcaster;

  @BeforeEach
  void setUp() {
    polls = new InMemoryPollRepository();
    votes = new InMemoryVoteRepository();
    hub = new SseHub();
    builder = new SnapshotBuilder(polls, votes);
    broadcaster = new TallyBroadcaster(hub, builder);
  }

  // @TS-030 — a VoteCastEvent fans out as a fresh "snapshot" SSE event whose tally reflects the
  // VoteRepository's current absolute counts for the voted question.
  @Test
  void vote_cast_event_broadcasts_fresh_snapshot() throws Exception {
    Poll poll = seedPollWithActiveQuestion("tally-talk");
    UUID activeQ = poll.activeQuestionId();
    UUID optionA = poll.questions().get(0).options().get(0).id();
    UUID optionB = poll.questions().get(0).options().get(1).id();
    votes.seedTally(activeQ, Map.of(optionA, 7L, optionB, 2L));
    CapturingEmitter emitter = new CapturingEmitter();
    hub.register(poll.id(), emitter);

    Instant at = Instant.parse("2026-04-19T10:00:00Z");
    broadcaster.onVoteCast(new VoteCastEvent(poll.id(), activeQ, at));

    assertThat(emitter.events).hasSize(1);
    CapturingEmitter.Captured event = emitter.events.get(0);
    assertThat(event.name).isEqualTo("snapshot");
    SnapshotPayload payload = (SnapshotPayload) event.data;
    assertThat(payload.pollId()).isEqualTo(poll.id());
    assertThat(payload.activeQuestion()).isNotNull();
    assertThat(payload.activeQuestion().id()).isEqualTo(activeQ);
    Map<UUID, Long> byOption = new HashMap<>();
    payload.tally().forEach(t -> byOption.put(t.optionId(), t.count()));
    assertThat(byOption).containsEntry(optionA, 7L).containsEntry(optionB, 2L);
  }

  // A VoteRetractedEvent fans out as a fresh "snapshot" SSE event reflecting the post-retraction
  // tally. Same payload shape as the cast path — the client is fully snapshot-driven.
  @Test
  void vote_retracted_event_broadcasts_fresh_snapshot() throws Exception {
    Poll poll = seedPollWithActiveQuestion("retract-talk");
    UUID activeQ = poll.activeQuestionId();
    UUID optionA = poll.questions().get(0).options().get(0).id();
    votes.seedTally(activeQ, Map.of(optionA, 3L));
    CapturingEmitter emitter = new CapturingEmitter();
    hub.register(poll.id(), emitter);

    Instant at = Instant.parse("2026-04-19T10:05:00Z");
    broadcaster.onVoteRetracted(new VoteRetractedEvent(poll.id(), activeQ, at));

    assertThat(emitter.events).hasSize(1);
    CapturingEmitter.Captured event = emitter.events.get(0);
    assertThat(event.name).isEqualTo("snapshot");
    SnapshotPayload payload = (SnapshotPayload) event.data;
    assertThat(payload.activeQuestion().id()).isEqualTo(activeQ);
    Map<UUID, Long> byOption = new HashMap<>();
    payload.tally().forEach(t -> byOption.put(t.optionId(), t.count()));
    assertThat(byOption.get(optionA)).isEqualTo(3L);
  }

  // @TS-031 — ActiveQuestionChangedEvent triggers a fresh snapshot whose activeQuestion.id matches
  // the newly active question and whose tally covers every option with count 0 (freshly
  // activated).
  @Test
  void active_question_change_broadcasts_fresh_snapshot() throws Exception {
    Poll poll = seedPollWithTwoDraftQuestions("snapshot-talk");
    // Activate Q2 and check the snapshot covers Q2's options with zeroed tallies.
    UUID q2 = poll.questions().get(1).id();
    polls.activateQuestion(poll.id(), q2);
    CapturingEmitter emitter = new CapturingEmitter();
    hub.register(poll.id(), emitter);

    broadcaster.onActiveQuestionChanged(
        new PollActiveQuestionChangedEvent(poll.id(), q2, Instant.now()));

    assertThat(emitter.events).hasSize(1);
    CapturingEmitter.Captured ev = emitter.events.get(0);
    assertThat(ev.name).isEqualTo("snapshot");
    SnapshotPayload payload = (SnapshotPayload) ev.data;
    assertThat(payload.pollId()).isEqualTo(poll.id());
    assertThat(payload.slug()).isEqualTo("snapshot-talk");
    assertThat(payload.activeQuestion()).isNotNull();
    assertThat(payload.activeQuestion().id()).isEqualTo(q2);
    // Tally contains every option of Q2 with count 0 (no votes yet).
    assertThat(payload.tally()).hasSize(2);
    assertThat(payload.tally()).allMatch(t -> t.count() == 0L);
  }

  // A question-closed event (presenter closed without activating a successor) broadcasts the
  // "question-closed" SSE event verbatim; a later activation will fire its own snapshot.
  @Test
  void question_closed_event_is_broadcast_as_question_closed() throws Exception {
    Poll poll = seedPollWithActiveQuestion("close-talk");
    UUID activeId = poll.activeQuestionId();
    CapturingEmitter emitter = new CapturingEmitter();
    hub.register(poll.id(), emitter);

    broadcaster.onQuestionClosed(new PollQuestionClosedEvent(poll.id(), activeId, Instant.now()));

    CapturingEmitter.Captured ev = emitter.events.get(0);
    assertThat(ev.name).isEqualTo("question-closed");
    QuestionClosedPayload payload = (QuestionClosedPayload) ev.data;
    assertThat(payload.pollId()).isEqualTo(poll.id());
    assertThat(payload.questionId()).isEqualTo(activeId);
  }

  // Sanity check: an event for a pollId with no subscribers is a no-op (no exception, no
  // interaction). Maps to the real-world case of the first vote arriving before any deck has
  // connected.
  @Test
  void broadcast_with_no_subscribers_is_a_noop() throws Exception {
    Poll poll = seedPollWithActiveQuestion("silent-talk");
    // No emitters registered.
    broadcaster.onVoteCast(new VoteCastEvent(poll.id(), poll.activeQuestionId(), Instant.now()));
    // Nothing to assert besides "did not throw"; reaching here is the assertion.
    assertThat(hub.subscriberCount(poll.id())).isZero();
  }

  // SnapshotBuilder directly: when the poll has no active question (WAITING state, FR-008), the
  // payload's activeQuestion is null and tally is empty.
  @Test
  void snapshot_for_waiting_state_has_null_active_question() {
    Poll poll = seedPollWithTwoDraftQuestions("waiting-talk");
    SnapshotPayload payload = builder.build(poll.id()).orElseThrow();
    assertThat(payload.activeQuestion()).isNull();
    assertThat(payload.tally()).isEmpty();
    assertThat(payload.voterCount()).isZero();
  }

  // voterCount carries the ballots-cast figure (one row per ballot, regardless of how many options
  // the ballot picked). The multi-choice results footer needs this distinct from the
  // selections-summed totals in `tally` so it can render "{voters} voters · {selections} sel...".
  @Test
  void snapshot_carries_voter_count_distinct_from_selections() {
    Poll poll = seedPollWithActiveQuestion("voter-count-talk");
    UUID activeQ = poll.activeQuestionId();
    UUID optionA = poll.questions().get(0).options().get(0).id();
    UUID optionB = poll.questions().get(0).options().get(1).id();
    // Selections sum to 7, but the fake's voterCount is max-per-option (4) — see
    // InMemoryVoteRepository#voterCount javadoc for why that's good enough here.
    votes.seedTally(activeQ, Map.of(optionA, 4L, optionB, 3L));
    CapturingEmitter emitter = new CapturingEmitter();
    hub.register(poll.id(), emitter);

    broadcaster.onVoteCast(new VoteCastEvent(poll.id(), activeQ, Instant.now()));

    SnapshotPayload payload = (SnapshotPayload) emitter.events.get(0).data;
    long selections = payload.tally().stream().mapToLong(SnapshotPayload.TallyEntry::count).sum();
    assertThat(selections).isEqualTo(7L);
    assertThat(payload.voterCount()).isEqualTo(4L);
    // Per-question arity rides on the snapshot now too (so the voter UI / panel footer can
    // branch on it without a separate fetch).
    assertThat(payload.activeQuestion().minSelections()).isEqualTo(1);
    assertThat(payload.activeQuestion().maxSelections()).isEqualTo(1);
  }

  // ---------- fixtures -----------------------------------------------------

  private Poll seedPollWithActiveQuestion(String slug) {
    Poll poll = seedPollWithTwoDraftQuestions(slug);
    return polls.activateQuestion(poll.id(), poll.questions().get(0).id());
  }

  private Poll seedPollWithTwoDraftQuestions(String slug) {
    UUID pollId = UUID.randomUUID();
    UUID q1 = UUID.randomUUID();
    UUID q2 = UUID.randomUUID();
    List<Option> q1Options =
        List.of(
            new Option(UUID.randomUUID(), q1, "Yes", 0),
            new Option(UUID.randomUUID(), q1, "No", 1));
    List<Option> q2Options =
        List.of(
            new Option(UUID.randomUUID(), q2, "A", 0), new Option(UUID.randomUUID(), q2, "B", 1));
    Question question1 =
        new Question(q1, pollId, "Q1?", 0, QuestionStatus.DRAFT, 1, 1, q1Options, null, null);
    Question question2 =
        new Question(q2, pollId, "Q2?", 1, QuestionStatus.DRAFT, 1, 1, q2Options, null, null);
    Poll poll =
        new Poll(
            pollId,
            "alice",
            "Fixture",
            slug,
            PollStatus.DRAFT,
            null,
            List.of(question1, question2),
            List.of(), // allowedOrigins
            Instant.now(),
            Instant.now());
    polls.insert(poll);
    return poll;
  }

  /**
   * Captures every event the hub sends. Overrides {@link SseEmitter#send(SseEventBuilder)} and
   * parses the builder's {@code build()} output: Spring's real builder accumulates the {@code
   * event:<name>\ndata:\n} metadata as a {@code text/plain} {@code DataWithMediaType}, with the
   * caller's payload as a separate entry carrying its own media type (null for {@code data(Object)}
   * calls). The name is recovered from the metadata string.
   */
  private static final class CapturingEmitter extends SseEmitter {
    final List<Captured> events = new ArrayList<>();

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      // Spring's builder emits a text fragment carrying "event:<name>\ndata:\n…\n" alongside the
      // caller's payload object. The fragment may arrive with TEXT_PLAIN or no media type at all
      // depending on the Spring version, so we detect it by content rather than by media type.
      Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
      String name = null;
      Object payload = null;
      for (ResponseBodyEmitter.DataWithMediaType part : parts) {
        Object data = part.getData();
        if (data instanceof String s) {
          int idx = s.indexOf("event:");
          if (idx >= 0) {
            int start = idx + "event:".length();
            int end = s.indexOf('\n', start);
            name = end >= 0 ? s.substring(start, end) : s.substring(start);
          }
          // Either the leading "event:<name>\ndata:" fragment or the trailing "\n\n" fragment;
          // neither is the caller's payload.
        } else {
          payload = data;
        }
      }
      events.add(new Captured(name, payload));
    }

    record Captured(String name, Object data) {}
  }

  /** In-memory {@link PollRepository} — just enough to cover the broadcaster's calls. */
  private static final class InMemoryPollRepository implements PollRepository {
    private final Map<UUID, Poll> byId = new java.util.HashMap<>();

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
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean slugTaken(String slug, UUID excludingPollId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Poll updateHeader(UUID pollId, String title, String slug) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Poll replaceQuestions(UUID pollId, List<CreatePollCommand.QuestionUpdate> drafts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(UUID pollId) {
      byId.remove(pollId);
    }

    @Override
    public Poll activateQuestion(UUID pollId, UUID questionId) {
      Poll existing = byId.get(pollId);
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
      throw new UnsupportedOperationException();
    }

    @Override
    public Poll updateAllowedOrigins(UUID pollId, java.util.List<String> origins) {
      throw new UnsupportedOperationException();
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
    public java.util.Map<UUID, Long> voteCountByQuestion(UUID pollId) {
      return java.util.Map.of();
    }
  }

  private static final class InMemoryVoteRepository implements VoteRepository {
    private final Map<UUID, Map<UUID, Long>> tallies = new HashMap<>();

    void seedTally(UUID questionId, Map<UUID, Long> counts) {
      tallies.put(questionId, new HashMap<>(counts));
    }

    @Override
    public site.asm0dey.slidev.polls.core.domain.Vote insert(
        site.asm0dey.slidev.polls.core.domain.Vote vote) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean alreadyVoted(UUID questionId, String voterToken) {
      return false;
    }

    @Override
    public Map<UUID, Long> tally(UUID questionId) {
      return tallies.getOrDefault(questionId, Map.of());
    }

    @Override
    public long voterCount(UUID questionId) {
      // Test fake: VoteRepositoryImpl returns COUNT(*) on votes. Without a
      // ballot ledger here we approximate via the largest per-option count
      // (≥ true ballot count when the same voter picks multiple options;
      // equal in the single-choice tests this fake currently serves).
      Map<UUID, Long> t = tallies.get(questionId);
      if (t == null || t.isEmpty()) return 0L;
      return t.values().stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    @Override
    public int deleteForPoll(UUID pollId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Optional<List<UUID>> deleteByQuestionAndVoter(
        UUID questionId, String voterToken) {
      throw new UnsupportedOperationException();
    }
  }
}
