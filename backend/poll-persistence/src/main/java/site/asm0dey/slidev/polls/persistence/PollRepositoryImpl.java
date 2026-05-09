package site.asm0dey.slidev.polls.persistence;

import static org.jooq.impl.DSL.any;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_OPTIONS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_QUESTIONS;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.error.ActivationRejectedException;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * jOOQ-backed implementation of {@link PollRepository}. All methods are thin projections over the
 * generated {@code POLLS / POLL_QUESTIONS / POLL_OPTIONS} tables; the only piece of non-trivial SQL
 * is {@link #activateQuestion} which leans on the partial unique index defined in {@code
 * V1__core_tables.sql} to serialise concurrent activations (FR-004, {@code @TS-004}).
 *
 * <p>Transaction boundaries are supplied by the caller (typically Spring's {@code @Transactional}
 * on {@link site.asm0dey.slidev.polls.core.service.PollService}); this class does not open its own
 * transactions.
 */
@Repository
public class PollRepositoryImpl implements PollRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  public PollRepositoryImpl(DSLContext dsl, ObjectMapper objectMapper) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
  }

  @Override
  public Poll insert(Poll poll) {
    OffsetDateTime now = OffsetDateTime.now();
    dsl.insertInto(POLLS)
        .set(POLLS.ID, poll.id())
        .set(POLLS.OWNER_USERNAME, poll.ownerUsername())
        .set(POLLS.TITLE, poll.title())
        .set(POLLS.SLUG, poll.slug())
        .set(POLLS.STATUS, poll.status().name())
        .set(POLLS.STYLE, toJsonb(poll.style()))
        .set(POLLS.ACTIVE_QUESTION_ID, poll.activeQuestionId())
        .set(POLLS.ALLOWED_ORIGINS, poll.allowedOrigins().toArray(new String[0]))
        .set(POLLS.CREATED_AT, now)
        .set(POLLS.UPDATED_AT, now)
        .execute();
    insertQuestions(poll.id(), poll.questions());
    return findById(poll.id()).orElseThrow(() -> new NotFoundException(poll.id().toString()));
  }

  @Override
  public Optional<Poll> findById(UUID pollId) {
    return dsl.select(POLLS.fields())
        .select(QUESTIONS_FIELD)
        .from(POLLS)
        .where(POLLS.ID.eq(pollId))
        .fetchOptional()
        .map(this::toPoll);
  }

  @Override
  public Optional<Poll> findBySlug(String slug) {
    return dsl.select(POLLS.fields())
        .select(QUESTIONS_FIELD)
        .from(POLLS)
        .where(org.jooq.impl.DSL.lower(POLLS.SLUG).eq(slug.toLowerCase()))
        .fetchOptional()
        .map(this::toPoll);
  }

  @Override
  public List<Poll> findByOwner(String ownerUsername) {
    return dsl.select(POLLS.fields())
        .select(QUESTIONS_FIELD)
        .from(POLLS)
        .where(POLLS.OWNER_USERNAME.eq(ownerUsername))
        .orderBy(POLLS.CREATED_AT.desc())
        .fetch()
        .map(this::toPoll);
  }

  @Override
  public boolean slugTaken(String slug, UUID excludingPollId) {
    var base =
        dsl.selectOne()
            .from(POLLS)
            .where(org.jooq.impl.DSL.lower(POLLS.SLUG).eq(slug.toLowerCase()));
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
  public Poll replaceQuestions(UUID pollId, List<CreatePollCommand.QuestionDraft> questions) {
    dsl.update(POLLS)
        .setNull(POLLS.ACTIVE_QUESTION_ID)
        .set(POLLS.STATUS, PollStatus.DRAFT.name())
        .set(POLLS.UPDATED_AT, OffsetDateTime.now())
        .where(POLLS.ID.eq(pollId))
        .execute();
    dsl.deleteFrom(POLL_QUESTIONS).where(POLL_QUESTIONS.POLL_ID.eq(pollId)).execute();
    List<Question> drafts = new ArrayList<>(questions.size());
    for (int i = 0; i < questions.size(); i++) {
      CreatePollCommand.QuestionDraft draft = questions.get(i);
      UUID questionId = UUID.randomUUID();
      List<Option> options = new ArrayList<>(draft.options().size());
      for (int j = 0; j < draft.options().size(); j++) {
        options.add(new Option(UUID.randomUUID(), questionId, draft.options().get(j).label(), j));
      }
      drafts.add(
          new Question(
              questionId, pollId, draft.prompt(), i, QuestionStatus.DRAFT, options, null, null));
    }
    insertQuestions(pollId, drafts);
    return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
  }

  @Override
  public Poll updateStyle(UUID pollId, Map<String, Object> style) {
    int updated =
        dsl.update(POLLS)
            .set(POLLS.STYLE, toJsonb(style))
            .set(POLLS.UPDATED_AT, OffsetDateTime.now())
            .where(POLLS.ID.eq(pollId))
            .execute();
    if (updated == 0) {
      throw new NotFoundException("poll " + pollId + " does not exist");
    }
    return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
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
    var existing =
        dsl.selectFrom(POLL_QUESTIONS)
            .where(POLL_QUESTIONS.ID.eq(questionId).and(POLL_QUESTIONS.POLL_ID.eq(pollId)))
            .fetchOptional()
            .orElseThrow(
                () -> new NotFoundException("question " + questionId + " not in poll " + pollId));

    switch (QuestionStatus.valueOf(existing.getStatus())) {
      case ACTIVE -> {
        return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
      }
      // CLOSED questions are reopenable — slide navigation may bounce a
      // viewer back to an earlier question and the deck must be able to
      // re-activate it. Falls through to the ACTIVATE flow below, which
      // clears closed_at as part of the update.
      case CLOSED, DRAFT -> {}
    }

    OffsetDateTime now = OffsetDateTime.now();
    // poll_questions_active_timestamp_ck: `(status = 'ACTIVE') = (activated_at IS NOT NULL)`.
    // Clearing activated_at on ACTIVE -> CLOSED is mandatory; the constraint rolls the update
    // back otherwise. closed_at is the authoritative "when did this question stop accepting
    // votes?" timestamp.
    dsl.update(POLL_QUESTIONS)
        .set(POLL_QUESTIONS.STATUS, QuestionStatus.CLOSED.name())
        .setNull(POLL_QUESTIONS.ACTIVATED_AT)
        .set(POLL_QUESTIONS.CLOSED_AT, now)
        .where(
            POLL_QUESTIONS
                .POLL_ID
                .eq(pollId)
                .and(POLL_QUESTIONS.STATUS.eq(QuestionStatus.ACTIVE.name()))
                .and(POLL_QUESTIONS.ID.ne(questionId)))
        .execute();
    try {
      dsl.update(POLL_QUESTIONS)
          .set(POLL_QUESTIONS.STATUS, QuestionStatus.ACTIVE.name())
          .set(POLL_QUESTIONS.ACTIVATED_AT, now)
          .set(POLL_QUESTIONS.CLOSED_AT, (OffsetDateTime) null)
          .where(POLL_QUESTIONS.ID.eq(questionId))
          .execute();
    } catch (IntegrityConstraintViolationException | DataIntegrityViolationException e) {
      throw new ConcurrentActivationException(pollId, questionId, e);
    }
    dsl.update(POLLS)
        .set(POLLS.ACTIVE_QUESTION_ID, questionId)
        .set(POLLS.STATUS, PollStatus.OPEN.name())
        .set(POLLS.UPDATED_AT, now)
        .where(POLLS.ID.eq(pollId))
        .execute();
    return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
  }

  @Override
  public Poll closeActiveQuestion(UUID pollId) {
    OffsetDateTime now = OffsetDateTime.now();
    // poll_questions_active_timestamp_ck demands activated_at be NULL when status is not ACTIVE;
    // see the matching close branch inside activateQuestion.
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
    dsl.update(POLLS)
        .setNull(POLLS.ACTIVE_QUESTION_ID)
        .set(POLLS.UPDATED_AT, now)
        .where(POLLS.ID.eq(pollId))
        .execute();
    return findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
  }

  @Override
  public boolean isOriginAllowedByAnyPoll(String origin) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(POLLS)
            .where(val(origin).eq(any(POLLS.ALLOWED_ORIGINS))));
  }

  @Override
  public Poll updateAllowedOrigins(UUID pollId, List<String> origins) {
    return dsl.update(POLLS)
        .set(POLLS.ALLOWED_ORIGINS, origins.toArray(new String[0]))
        .set(POLLS.UPDATED_AT, OffsetDateTime.now())
        .where(POLLS.ID.eq(pollId))
        .returningResult(pollFieldsWithQuestions())
        .fetchOptional()
        .map(this::toPoll)
        .orElseThrow(() -> new NotFoundException("poll " + pollId + " does not exist"));
  }

  /**
   * Combine the {@link site.asm0dey.slidev.polls.persistence.jooq.tables.Polls} columns with the
   * {@link #QUESTIONS_FIELD} multiset so an {@code UPDATE … RETURNING} can hand back a fully
   * hydrated poll aggregate in a single Postgres round-trip — no follow-up {@code SELECT}.
   */
  private static org.jooq.SelectField<?>[] pollFieldsWithQuestions() {
    Field<?>[] cols = POLLS.fields();
    org.jooq.SelectField<?>[] all = new org.jooq.SelectField<?>[cols.length + 1];
    System.arraycopy(cols, 0, all, 0, cols.length);
    all[cols.length] = QUESTIONS_FIELD;
    return all;
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
              .set(POLL_QUESTIONS.ORDINAL, q.ordinal())
              .set(POLL_QUESTIONS.STATUS, q.status().name())
              .execute();
          List<Option> insertedOptions = new ArrayList<>(q.options().size());
          for (Option o : q.options()) {
              UUID oid = o.id() != null ? o.id() : UUID.randomUUID();
              dsl.insertInto(POLL_OPTIONS)
                  .set(POLL_OPTIONS.ID, oid)
                  .set(POLL_OPTIONS.QUESTION_ID, qid)
                  .set(POLL_OPTIONS.LABEL, o.label())
                  .set(POLL_OPTIONS.POSITION, o.position())
                  .execute();
              insertedOptions.add(new Option(oid, qid, o.label(), o.position()));
          }
          ordered.add(
              new Question(
                  qid, pollId, q.prompt(), q.ordinal(), q.status(), insertedOptions, null, null));
      }
    return ordered;
  }

  /**
   * Inner multiset that materialises every {@link Option} for the surrounding {@link Question} row
   * — correlated by {@code POLL_OPTIONS.QUESTION_ID = POLL_QUESTIONS.ID}. Used inside
   * {@link #QUESTIONS_FIELD}; never emitted on its own.
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
          .convertFrom(
              r -> r.map(o -> new Option(o.value1(), o.value2(), o.value3(), o.value4())));

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
                              q.get(OPTIONS_FIELD),
                              q.get(POLL_QUESTIONS.ACTIVATED_AT) == null ? null : q.get(POLL_QUESTIONS.ACTIVATED_AT).toInstant(),
                              q.get(POLL_QUESTIONS.CLOSED_AT) == null ? null : q.get(POLL_QUESTIONS.CLOSED_AT).toInstant())));

  private Poll toPoll(Record row) {
    String[] originsArr = row.get(POLLS.ALLOWED_ORIGINS);
    return new Poll(
        row.get(POLLS.ID),
        row.get(POLLS.OWNER_USERNAME),
        row.get(POLLS.TITLE),
        row.get(POLLS.SLUG),
        PollStatus.valueOf(row.get(POLLS.STATUS)),
        fromJsonb(row.get(POLLS.STYLE)),
        row.get(POLLS.ACTIVE_QUESTION_ID),
        row.get(QUESTIONS_FIELD),
        originsArr == null ? List.of() : List.of(originsArr),
        row.get(POLLS.CREATED_AT).toInstant(),
        row.get(POLLS.UPDATED_AT).toInstant());
  }

  private JSONB toJsonb(Map<String, Object> style) {
    if (style == null || style.isEmpty()) {
      return JSONB.jsonb("{}");
    }
    try {
      return JSONB.jsonb(objectMapper.writeValueAsString(style));
    } catch (JacksonException e) {
      throw new IllegalStateException("cannot serialise poll style to jsonb", e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> fromJsonb(JSONB value) {
    if (value == null) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(value.data(), LinkedHashMap.class);
    } catch (Exception e) {
      throw new IllegalStateException("cannot read poll style from jsonb", e);
    }
  }

  /**
   * Thrown by {@link #activateQuestion} when the partial unique index {@code
   * poll_questions_one_active_uq} refuses a second concurrent activation on the same poll (FR-004,
   * {@code @TS-004}). Callers translate this into a presenter-visible error via {@link
   * org.springframework.retry.support.RetryTemplate} or by surfacing the failure to the other
   * transaction that raced.
   */
  public static final class ConcurrentActivationException extends RuntimeException {
    public ConcurrentActivationException(UUID pollId, UUID questionId, Throwable cause) {
      super("concurrent activation race for poll " + pollId + " question " + questionId, cause);
    }
  }
}
