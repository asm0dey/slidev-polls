package site.asm0dey.slidev.polls.realtime.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
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
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import site.asm0dey.slidev.polls.core.service.VoteRepository;
import site.asm0dey.slidev.polls.realtime.SseHub;

/**
 * Covers the SSE broadcast contract:
 *
 * <ul>
 *   <li>{@code @TS-030} — snapshot on connect / tally on vote
 *   <li>{@code @TS-031} — fresh snapshot on active-question change
 *   <li>{@code @TS-032} — stray tally (wrong questionId) is still broadcast by the server and
 *       filtered by the client; the server-side assertion is that a {@code tally} event carries the
 *       exact {@code questionId} the broadcaster observed, so a stale event can be detected
 *   <li>{@code question-closed} on presenter-driven close
 * </ul>
 *
 * Pure-Java fakes; no Spring. The {@link SseHub} is a real instance (its concurrency is pinned by
 * {@code SseHubConcurrencyTest}); everything else is in-memory.
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

  // @TS-030 — a VoteCastEvent fans out as a "tally" SSE event carrying the new absolute count for
  // the voted option. The subscriber captures the event name and payload so we can assert both.
  @Test
  void vote_cast_event_broadcasts_tally_with_new_count() throws Exception {
    Poll poll = seedPollWithActiveQuestion("tally-talk");
    UUID optionA = poll.questions().get(0).options().get(0).id();
    CapturingEmitter emitter = new CapturingEmitter();
    hub.register(poll.id(), emitter);

    Instant at = Instant.parse("2026-04-19T10:00:00Z");
    broadcaster.onVoteCast(new VoteCastEvent(poll.id(), poll.activeQuestionId(), optionA, 7L, at));

    assertThat(emitter.events).hasSize(1);
    CapturingEmitter.Captured event = emitter.events.get(0);
    assertThat(event.name).isEqualTo("tally");
    TallyPayload payload = (TallyPayload) event.data;
    assertThat(payload.pollId()).isEqualTo(poll.id());
    assertThat(payload.questionId()).isEqualTo(poll.activeQuestionId());
    assertThat(payload.optionId()).isEqualTo(optionA);
    assertThat(payload.count()).isEqualTo(7L);
    assertThat(payload.emittedAt()).isEqualTo(at);
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

  // @TS-032 — the broadcaster always stamps the event with the questionId from the VoteCastEvent,
  // so the client can detect a stale tally and ignore it. Server-side assertion: a tally event
  // carries the exact questionId the event said it did, never an out-of-band value.
  @Test
  void tally_payload_carries_the_votecast_question_id_for_client_filtering() throws Exception {
    Poll poll = seedPollWithActiveQuestion("filter-talk");
    UUID optionA = poll.questions().get(0).options().get(0).id();
    UUID someOtherQuestion = UUID.randomUUID();
    CapturingEmitter emitter = new CapturingEmitter();
    hub.register(poll.id(), emitter);

    // Fire an event whose questionId intentionally does not match the current active. The
    // broadcaster still forwards it verbatim — the client is the one that filters stray tallies
    // by cross-referencing its latest snapshot's activeQuestion.id.
    broadcaster.onVoteCast(
        new VoteCastEvent(poll.id(), someOtherQuestion, optionA, 1L, Instant.now()));

    TallyPayload payload = (TallyPayload) emitter.events.get(0).data;
    assertThat(payload.questionId())
        .as("payload carries the exact questionId — client filters stray tallies via it")
        .isEqualTo(someOtherQuestion);
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
    UUID optionA = poll.questions().get(0).options().get(0).id();
    // No emitters registered.
    broadcaster.onVoteCast(
        new VoteCastEvent(poll.id(), poll.activeQuestionId(), optionA, 1L, Instant.now()));
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
        new Question(q1, pollId, "Q1?", 0, QuestionStatus.DRAFT, q1Options, null, null);
    Question question2 =
        new Question(q2, pollId, "Q2?", 1, QuestionStatus.DRAFT, q2Options, null, null);
    Poll poll =
        new Poll(
            pollId,
            "alice",
            "Fixture",
            slug,
            PollStatus.DRAFT,
            Map.of(),
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
    public Poll replaceQuestions(UUID pollId, List<CreatePollCommand.QuestionDraft> drafts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Poll updateStyle(UUID pollId, Map<String, Object> style) {
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
      throw new UnsupportedOperationException();
    }
  }

  private static final class InMemoryVoteRepository implements VoteRepository {
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
      return Map.of();
    }
  }
}
