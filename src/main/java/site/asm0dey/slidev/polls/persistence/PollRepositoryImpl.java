package site.asm0dey.slidev.polls.persistence;

import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_ALLOWED_ORIGINS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_OPTIONS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_QUESTIONS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.VOTES;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.Field;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;
import site.asm0dey.slidev.polls.core.service.PollRepository;

/**
 * jOOQ-backed implementation of {@link PollRepository}. All methods are thin projections over the
 * generated {@code POLLS / POLL_QUESTIONS / POLL_OPTIONS} tables. The canonical "which question is
 * active" state lives on {@code poll_questions.status}; {@link #activateQuestion} is a single
 * CASE-driven UPDATE that flips the target question to ACTIVE and closes every other row of the
 * poll in one statement, relying on the partial unique index defined in {@code V1__core_tables.sql}
 * to serialise concurrent activations (FR-004, {@code @TS-004}).
 *
 * <p>Transaction boundaries are supplied by the caller (typically Spring's {@code @Transactional}
 * on {@link site.asm0dey.slidev.polls.core.service.PollService}); this class does not open its own
 * transactions.
 */
@Repository
public class PollRepositoryImpl implements PollRepository {

  private final DSLContext dsl;

  public PollRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Poll insert(Poll poll) {
    OffsetDateTime now = OffsetDateTime.now();
    dsl.insertInto(POLLS)
        .set(POLLS.ID, poll.id())
        .set(POLLS.OWNER_USERNAME, poll.ownerUsername())
        .set(POLLS.TITLE, poll.title())
        .set(POLLS.SLUG, poll.slug())
        .set(POLLS.CREATED_AT, now)
        .set(POLLS.UPDATED_AT, now)
        .execute();
    writeOrigins(poll.id(), poll.allowedOrigins());
    insertQuestions(poll.id(), poll.questions());
    return findById(poll.id()).orElseThrow(() -> new NotFoundException(poll.id().toString()));
  }

  @Override
  public Optional<Poll> findById(UUID pollId) {
    return dsl.select(POLLS.fields())
        .select(QUESTIONS_FIELD)
        .select(ORIGINS_FIELD)
        .from(POLLS)
        .where(POLLS.ID.eq(pollId))
        .fetchOptional()
        .map(this::toPoll);
  }

  @Override
  public Optional<Poll> findBySlug(String slug) {
    return dsl.select(POLLS.fields())
        .select(QUESTIONS_FIELD)
        .select(ORIGINS_FIELD)
        .from(POLLS)
        .where(POLLS.SLUG_LOWER.eq(slug.toLowerCase()))
        .fetchOptional()
        .map(this::toPoll);
  }

  @Override
  public List<Poll> findByOwner(String ownerUsername) {
    return dsl.select(POLLS.fields())
        .select(QUESTIONS_FIELD)
        .select(ORIGINS_FIELD)
        .from(POLLS)
        .where(POLLS.OWNER_USERNAME.eq(ownerUsername))
        .orderBy(POLLS.CREATED_AT.desc())
        .fetch()
        .map(this::toPoll);
  }

  @Override
  public boolean slugTaken(String slug, UUID excludingPollId) {
    var base = dsl.selectOne().from(POLLS).where(POLLS.SLUG_LOWER.eq(slug.toLowerCase()));
    var scoped = excludingPollId == null ? base : base.and(POLLS.ID.ne(excludingPollId));
    return scoped.fetchOptional().isPresent();
  }

  @Override
  public Poll updateHeader(UUID pollId, String title, String slug) {
    int updated =
        dsl.update(POLLS)
            .set(POLLS.TITLE, title)
            .set(POLLS.SLUG, slug)
            .set(POLLS.UPDATED_AT, OffsetDateTime.now())
            .where(POLLS.ID.eq(pollId))
            .execute();
    if (updated == 0) {
      throw new NotFoundException("poll " + pollId + " does not exist");
    }
    return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
  }

  @Override
  public Poll replaceQuestions(UUID pollId, List<CreatePollCommand.QuestionUpdate> incoming) {
    java.util.Set<UUID> existingQuestionIds =
        new java.util.HashSet<>(
            dsl.select(POLL_QUESTIONS.ID)
                .from(POLL_QUESTIONS)
                .where(POLL_QUESTIONS.POLL_ID.eq(pollId))
                .fetch(POLL_QUESTIONS.ID));

    java.util.Set<UUID> keepQuestionIds = new java.util.HashSet<>();
    for (CreatePollCommand.QuestionUpdate q : incoming) {
      if (q.id() != null) keepQuestionIds.add(q.id());
    }
    java.util.Set<UUID> toDelete = new java.util.HashSet<>(existingQuestionIds);
    toDelete.removeAll(keepQuestionIds);
    if (!toDelete.isEmpty()) {
      dsl.deleteFrom(POLL_QUESTIONS).where(POLL_QUESTIONS.ID.in(toDelete)).execute();
    }

    for (int i = 0; i < incoming.size(); i++) {
      CreatePollCommand.QuestionUpdate q = incoming.get(i);
      UUID qid = (q.id() != null && existingQuestionIds.contains(q.id())) ? q.id() : null;
      if (qid != null) {
        dsl.update(POLL_QUESTIONS)
            .set(POLL_QUESTIONS.PROMPT, q.prompt())
            .set(POLL_QUESTIONS.ORDINAL, i)
            .set(POLL_QUESTIONS.MIN_SELECTIONS, q.minSelections())
            .set(POLL_QUESTIONS.MAX_SELECTIONS, q.maxSelections())
            .where(POLL_QUESTIONS.ID.eq(qid))
            .execute();
        syncOptions(qid, q.options());
      } else {
        UUID newQid = UUID.randomUUID();
        dsl.insertInto(POLL_QUESTIONS)
            .set(POLL_QUESTIONS.ID, newQid)
            .set(POLL_QUESTIONS.POLL_ID, pollId)
            .set(POLL_QUESTIONS.PROMPT, q.prompt())
            .set(POLL_QUESTIONS.MIN_SELECTIONS, q.minSelections())
            .set(POLL_QUESTIONS.MAX_SELECTIONS, q.maxSelections())
            .set(POLL_QUESTIONS.ORDINAL, i)
            .set(POLL_QUESTIONS.STATUS, QuestionStatus.DRAFT.name())
            .execute();
        insertNewOptions(newQid, q.options());
      }
    }
    return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
  }

  private void syncOptions(UUID questionId, List<CreatePollCommand.OptionUpdate> incoming) {
    java.util.Set<UUID> existing =
        new java.util.HashSet<>(
            dsl.select(POLL_OPTIONS.ID)
                .from(POLL_OPTIONS)
                .where(POLL_OPTIONS.QUESTION_ID.eq(questionId))
                .fetch(POLL_OPTIONS.ID));
    java.util.Set<UUID> keep = new java.util.HashSet<>();
    for (CreatePollCommand.OptionUpdate o : incoming) {
      if (o.id() != null) keep.add(o.id());
    }
    java.util.Set<UUID> toDelete = new java.util.HashSet<>(existing);
    toDelete.removeAll(keep);
    if (!toDelete.isEmpty()) {
      dsl.deleteFrom(POLL_OPTIONS).where(POLL_OPTIONS.ID.in(toDelete)).execute();
    }
    for (int i = 0; i < incoming.size(); i++) {
      CreatePollCommand.OptionUpdate o = incoming.get(i);
      if (o.id() != null && existing.contains(o.id())) {
        dsl.update(POLL_OPTIONS)
            .set(POLL_OPTIONS.LABEL, o.label())
            .set(POLL_OPTIONS.POSITION, i)
            .where(POLL_OPTIONS.ID.eq(o.id()))
            .execute();
      } else {
        dsl.insertInto(POLL_OPTIONS)
            .set(POLL_OPTIONS.ID, UUID.randomUUID())
            .set(POLL_OPTIONS.QUESTION_ID, questionId)
            .set(POLL_OPTIONS.LABEL, o.label())
            .set(POLL_OPTIONS.POSITION, i)
            .execute();
      }
    }
  }

  private void insertNewOptions(UUID questionId, List<CreatePollCommand.OptionUpdate> options) {
    for (int i = 0; i < options.size(); i++) {
      CreatePollCommand.OptionUpdate o = options.get(i);
      dsl.insertInto(POLL_OPTIONS)
          .set(POLL_OPTIONS.ID, UUID.randomUUID())
          .set(POLL_OPTIONS.QUESTION_ID, questionId)
          .set(POLL_OPTIONS.LABEL, o.label())
          .set(POLL_OPTIONS.POSITION, i)
          .execute();
    }
  }

  @Override
  public void delete(UUID pollId) {
    int deleted = dsl.deleteFrom(POLLS).where(POLLS.ID.eq(pollId)).execute();
    if (deleted == 0) {
      throw new NotFoundException("poll " + pollId + " does not exist");
    }
  }

  @Override
  public Poll activateQuestion(UUID pollId, UUID questionId) {
    // Canonical state lives only on poll_questions.status. We flip every row of the poll in a
    // single CASE-driven UPDATE:
    //   - the target row becomes ACTIVE (activated_at = now, closed_at = NULL),
    //   - any currently-ACTIVE row that is not the target becomes CLOSED
    //     (activated_at = NULL, closed_at = now),
    //   - every other row keeps its existing status / timestamps.
    // The partial unique index poll_questions_one_active_uq serialises concurrent activations
    // on the same poll (FR-004) — both engines lock the index slot for the duration of the
    // statement. polls.updated_at is maintained by a trigger that fires on poll_questions
    // changes (V10 / H2 V2).
    int matches =
        dsl.fetchCount(
            POLL_QUESTIONS,
            POLL_QUESTIONS.POLL_ID.eq(pollId).and(POLL_QUESTIONS.ID.eq(questionId)));
    if (matches == 0) {
      throw new NotFoundException("question " + questionId + " not in poll " + pollId);
    }

    OffsetDateTime now = OffsetDateTime.now();
    // poll_questions_active_timestamp_ck demands (status='ACTIVE') = (activated_at IS NOT NULL).
    // The CASE expressions keep that invariant by setting activated_at=now only when status
    // transitions to ACTIVE and clearing it when an ACTIVE row is closed in the same statement.
    dsl.update(POLL_QUESTIONS)
        .set(
            POLL_QUESTIONS.STATUS,
            when(POLL_QUESTIONS.ID.eq(questionId), val(QuestionStatus.ACTIVE.name()))
                .when(
                    POLL_QUESTIONS.STATUS.eq(QuestionStatus.ACTIVE.name()),
                    val(QuestionStatus.CLOSED.name()))
                .otherwise(POLL_QUESTIONS.STATUS))
        .set(
            POLL_QUESTIONS.ACTIVATED_AT,
            when(POLL_QUESTIONS.ID.eq(questionId), val(now))
                .when(
                    POLL_QUESTIONS.STATUS.eq(QuestionStatus.ACTIVE.name()),
                    val((OffsetDateTime) null))
                .otherwise(POLL_QUESTIONS.ACTIVATED_AT))
        .set(
            POLL_QUESTIONS.CLOSED_AT,
            when(POLL_QUESTIONS.ID.eq(questionId), val((OffsetDateTime) null))
                .when(POLL_QUESTIONS.STATUS.eq(QuestionStatus.ACTIVE.name()), val(now))
                .otherwise(POLL_QUESTIONS.CLOSED_AT))
        .where(POLL_QUESTIONS.POLL_ID.eq(pollId))
        .execute();
    return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
  }

  @Override
  public Poll closeActiveQuestion(UUID pollId) {
    OffsetDateTime now = OffsetDateTime.now();
    // poll_questions_active_timestamp_ck demands activated_at be NULL when status is not ACTIVE.
    dsl.update(POLL_QUESTIONS)
        .set(POLL_QUESTIONS.STATUS, QuestionStatus.CLOSED.name())
        .setNull(POLL_QUESTIONS.ACTIVATED_AT)
        .set(POLL_QUESTIONS.CLOSED_AT, now)
        .where(
            POLL_QUESTIONS
                .POLL_ID
                .eq(pollId)
                .and(POLL_QUESTIONS.STATUS.eq(QuestionStatus.ACTIVE.name())))
        .execute();
    return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
  }

  @Override
  public Poll resetQuestionsToDraft(UUID pollId) {
    dsl.update(POLL_QUESTIONS)
        .set(POLL_QUESTIONS.STATUS, QuestionStatus.DRAFT.name())
        .setNull(POLL_QUESTIONS.ACTIVATED_AT)
        .setNull(POLL_QUESTIONS.CLOSED_AT)
        .where(POLL_QUESTIONS.POLL_ID.eq(pollId))
        .execute();
    return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
  }

  @Override
  public boolean isOriginAllowedByAnyPoll(String origin) {
    return dsl.fetchExists(
        dsl.selectOne().from(POLL_ALLOWED_ORIGINS).where(POLL_ALLOWED_ORIGINS.ORIGIN.eq(origin)));
  }

  @Override
  public Poll updateAllowedOrigins(UUID pollId, List<String> origins) {
    dsl.deleteFrom(POLL_ALLOWED_ORIGINS).where(POLL_ALLOWED_ORIGINS.POLL_ID.eq(pollId)).execute();
    writeOrigins(pollId, origins);
    int touched =
        dsl.update(POLLS)
            .set(POLLS.UPDATED_AT, OffsetDateTime.now())
            .where(POLLS.ID.eq(pollId))
            .execute();
    if (touched == 0) throw new NotFoundException("poll " + pollId + " does not exist");
    return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
  }

  private void writeOrigins(UUID pollId, List<String> origins) {
    if (origins == null || origins.isEmpty()) return;
    var rows =
        java.util.stream.IntStream.range(0, origins.size())
            .mapToObj(
                i ->
                    dsl.insertInto(POLL_ALLOWED_ORIGINS)
                        .set(POLL_ALLOWED_ORIGINS.POLL_ID, pollId)
                        .set(POLL_ALLOWED_ORIGINS.ORIGIN, origins.get(i))
                        .set(POLL_ALLOWED_ORIGINS.POSITION, i))
            .toList();
    dsl.batch(rows).execute();
  }

  private List<Question> insertQuestions(UUID pollId, List<Question> questions) {
    if (questions == null || questions.isEmpty()) {
      return List.of();
    }
    List<Question> ordered = new ArrayList<>(questions.size());
    for (Question q : questions) {
      UUID qid = q.id() != null ? q.id() : UUID.randomUUID();
      dsl.insertInto(POLL_QUESTIONS)
          .set(POLL_QUESTIONS.ID, qid)
          .set(POLL_QUESTIONS.POLL_ID, pollId)
          .set(POLL_QUESTIONS.PROMPT, q.prompt())
          .set(POLL_QUESTIONS.MIN_SELECTIONS, q.minSelections())
          .set(POLL_QUESTIONS.MAX_SELECTIONS, q.maxSelections())
          .set(POLL_QUESTIONS.ORDINAL, q.ordinal())
          .set(POLL_QUESTIONS.STATUS, q.status().name())
          .execute();
      List<Option> insertedOptions = new ArrayList<>(q.options().size());
      if (!q.options().isEmpty()) {
        var batch =
            dsl.batch(
                q.options().stream()
                    .map(
                        o -> {
                          UUID oid = o.id() != null ? o.id() : UUID.randomUUID();
                          insertedOptions.add(new Option(oid, qid, o.label(), o.position()));
                          return dsl.insertInto(POLL_OPTIONS)
                              .set(POLL_OPTIONS.ID, oid)
                              .set(POLL_OPTIONS.QUESTION_ID, qid)
                              .set(POLL_OPTIONS.LABEL, o.label())
                              .set(POLL_OPTIONS.POSITION, o.position());
                        })
                    .toList());
        batch.execute();
      }
      ordered.add(
          new Question(
              qid,
              pollId,
              q.prompt(),
              q.ordinal(),
              q.status(),
              q.minSelections(),
              q.maxSelections(),
              insertedOptions,
              null,
              null));
    }
    return ordered;
  }

  /**
   * Inner multiset that materialises every {@link Option} for the surrounding {@link Question} row
   * — correlated by {@code POLL_OPTIONS.QUESTION_ID = POLL_QUESTIONS.ID}. Used inside {@link
   * #QUESTIONS_FIELD}; never emitted on its own.
   */
  private static final Field<List<Option>> OPTIONS_FIELD =
      multiset(
              select(
                      POLL_OPTIONS.ID,
                      POLL_OPTIONS.QUESTION_ID,
                      POLL_OPTIONS.LABEL,
                      POLL_OPTIONS.POSITION)
                  .from(POLL_OPTIONS)
                  .where(POLL_OPTIONS.QUESTION_ID.eq(POLL_QUESTIONS.ID))
                  .orderBy(POLL_OPTIONS.POSITION.asc()))
          .convertFrom(r -> r.map(o -> new Option(o.value1(), o.value2(), o.value3(), o.value4())));

  /**
   * Outer multiset that materialises every {@link Question} (with its nested options) for the poll
   * row in scope — correlated by {@code POLL_QUESTIONS.POLL_ID = POLLS.ID}. The whole poll
   * aggregate is therefore one round-trip; on Postgres jOOQ emits a single SELECT with two
   * subselects rendered as JSON arrays.
   */
  private static final Field<List<Question>> QUESTIONS_FIELD =
      multiset(
              select(
                      POLL_QUESTIONS.ID,
                      POLL_QUESTIONS.POLL_ID,
                      POLL_QUESTIONS.PROMPT,
                      POLL_QUESTIONS.ORDINAL,
                      POLL_QUESTIONS.STATUS,
                      POLL_QUESTIONS.MIN_SELECTIONS,
                      POLL_QUESTIONS.MAX_SELECTIONS,
                      POLL_QUESTIONS.ACTIVATED_AT,
                      POLL_QUESTIONS.CLOSED_AT,
                      OPTIONS_FIELD)
                  .from(POLL_QUESTIONS)
                  .where(POLL_QUESTIONS.POLL_ID.eq(POLLS.ID))
                  .orderBy(POLL_QUESTIONS.ORDINAL.asc()))
          .convertFrom(
              r ->
                  r.map(
                      q ->
                          new Question(
                              q.get(POLL_QUESTIONS.ID),
                              q.get(POLL_QUESTIONS.POLL_ID),
                              q.get(POLL_QUESTIONS.PROMPT),
                              q.get(POLL_QUESTIONS.ORDINAL),
                              QuestionStatus.valueOf(q.get(POLL_QUESTIONS.STATUS)),
                              q.get(POLL_QUESTIONS.MIN_SELECTIONS),
                              q.get(POLL_QUESTIONS.MAX_SELECTIONS),
                              q.get(OPTIONS_FIELD),
                              q.get(POLL_QUESTIONS.ACTIVATED_AT) == null
                                  ? null
                                  : q.get(POLL_QUESTIONS.ACTIVATED_AT).toInstant(),
                              q.get(POLL_QUESTIONS.CLOSED_AT) == null
                                  ? null
                                  : q.get(POLL_QUESTIONS.CLOSED_AT).toInstant())));

  private static final Field<List<String>> ORIGINS_FIELD =
      multiset(
              select(POLL_ALLOWED_ORIGINS.ORIGIN)
                  .from(POLL_ALLOWED_ORIGINS)
                  .where(POLL_ALLOWED_ORIGINS.POLL_ID.eq(POLLS.ID))
                  .orderBy(POLL_ALLOWED_ORIGINS.POSITION.asc()))
          .convertFrom(r -> r.map(o -> o.value1()));

  private Poll toPoll(Record row) {
    List<Question> questions = row.get(QUESTIONS_FIELD);
    UUID activeQuestionId =
        questions.stream()
            .filter(q -> q.status() == QuestionStatus.ACTIVE)
            .findFirst()
            .map(Question::id)
            .orElse(null);
    PollStatus status =
        questions.stream().anyMatch(q -> q.status() == QuestionStatus.ACTIVE)
            ? PollStatus.OPEN
            : PollStatus.DRAFT;
    return new Poll(
        row.get(POLLS.ID),
        row.get(POLLS.OWNER_USERNAME),
        row.get(POLLS.TITLE),
        row.get(POLLS.SLUG),
        status,
        activeQuestionId,
        questions,
        row.get(ORIGINS_FIELD),
        row.get(POLLS.CREATED_AT).toInstant(),
        row.get(POLLS.UPDATED_AT).toInstant());
  }

  @Override
  public Map<UUID, Long> voteCountByQuestion(UUID pollId) {
    Map<UUID, Long> out = new HashMap<>();
    dsl.select(VOTES.QUESTION_ID, DSL.count())
        .from(VOTES)
        .where(VOTES.POLL_ID.eq(pollId))
        .groupBy(VOTES.QUESTION_ID)
        .fetch()
        .forEach(r -> out.put(r.value1(), (long) r.value2()));
    return out;
  }
}
