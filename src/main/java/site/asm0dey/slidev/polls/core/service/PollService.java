package site.asm0dey.slidev.polls.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.error.ActivationRejectedException;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.error.NotOwnerException;
import site.asm0dey.slidev.polls.core.error.ResourceHasVotesException;
import site.asm0dey.slidev.polls.core.error.SlugInvalidException;
import site.asm0dey.slidev.polls.core.error.SlugReservedException;
import site.asm0dey.slidev.polls.core.error.SlugTakenException;
import site.asm0dey.slidev.polls.core.event.PollActiveQuestionChangedEvent;
import site.asm0dey.slidev.polls.core.event.PollQuestionClosedEvent;
import site.asm0dey.slidev.polls.core.event.PollVotesClearedEvent;
import site.asm0dey.slidev.polls.core.origin.OriginNormaliser;
import site.asm0dey.slidev.polls.core.slug.ReservedSlugs;
import site.asm0dey.slidev.polls.core.slug.SlugDeriver;
import site.asm0dey.slidev.polls.core.slug.SlugValidator;

/**
 * Presenter-authored poll lifecycle. Methods suffixed with {@code ForOwner} enforce ownership
 * (FR-001) — anything but the owning presenter surfaces {@link NotOwnerException}. The deck path
 * uses {@link #activateQuestion(UUID, UUID)} directly, because the {@code
 * DeckTokenAuthenticationFilter} has already validated the token/poll binding by the time we get
 * here.
 */
@ApplicationScoped
public class PollService {

  private final PollRepository repository;
  private final Event<Object> events;
  // Self-injection so intra-class calls go through the CDI client proxy and therefore
  // cross a @Transactional interceptor boundary (a direct `this.method()` call would
  // bypass the interceptor). CDI's Instance is the analog of Spring's ObjectProvider:
  // it resolves the contextual proxy on demand via `get()`.
  private final Instance<PollService> self;
  private final VoteRepository voteRepository;

  public PollService(
      PollRepository repository,
      Event<Object> events,
      Instance<PollService> self,
      VoteRepository voteRepository) {
    this.repository = repository;
    this.events = events;
    this.self = self;
    this.voteRepository = voteRepository;
  }

  @Transactional
  public Poll create(String ownerUsername, CreatePollCommand command) {
    String slug = resolveSlug(command.slug(), command.title(), null);
    UUID pollId = UUID.randomUUID();
    Instant now = Instant.now();
    List<Question> questions = buildDraftQuestions(pollId, command.questions());
    List<String> origins =
        OriginNormaliser.normalise(
            command.allowedOrigins() == null ? List.of() : command.allowedOrigins());
    Poll poll =
        new Poll(
            pollId,
            ownerUsername,
            command.title(),
            slug,
            PollStatus.DRAFT,
            null,
            questions,
            origins,
            now,
            now);
    return repository.insert(poll);
  }

  @Transactional
  public List<Poll> listForOwner(String ownerUsername) {
    return repository.findByOwner(ownerUsername);
  }

  @Transactional
  public Poll getForOwner(UUID pollId, String ownerUsername) {
    Poll poll =
        repository.findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
    requireOwner(poll, ownerUsername);
    return poll;
  }

  @Transactional
  public Poll updateForOwner(UUID pollId, String ownerUsername, UpdatePollCommand command) {
    Poll existing = self.get().getForOwner(pollId, ownerUsername);
    String title = command.title() != null ? command.title() : existing.title();
    String slug = existing.slug();
    if (command.slug() != null && !command.slug().equals(existing.slug())) {
      slug = resolveSlug(command.slug(), title, existing.id());
    }
    Poll afterHeader = repository.updateHeader(pollId, title, slug);
    if (command.allowedOrigins() != null) {
      afterHeader =
          repository.updateAllowedOrigins(
              pollId, OriginNormaliser.normalise(command.allowedOrigins()));
    }
    if (command.questions() != null) {
      enforceVoteLock(existing, command.questions());
      return repository.replaceQuestions(pollId, command.questions());
    }
    return afterHeader;
  }

  /**
   * FR-013 / RESOURCE_HAS_VOTES: once a question has recorded votes, structural edits to it are
   * rejected. Reword-only edits (prompt text, option label text) and pure reorderings remain
   * allowed. Structural edits are:
   *
   * <ul>
   *   <li>deleting the question (its id is missing from the update payload),
   *   <li>deleting any of its options (an existing option id is missing from the update payload),
   *   <li>changing its selection arity ({@code minSelections} / {@code maxSelections}).
   * </ul>
   */
  private void enforceVoteLock(Poll existing, List<CreatePollCommand.QuestionUpdate> incoming) {
    Map<UUID, Long> counts = repository.voteCountByQuestion(existing.id());
    if (counts.isEmpty()) {
      return;
    }
    Set<UUID> incomingQuestionIds = collectIncomingQuestionIds(incoming);
    lockQuestionDelete(existing, counts, incomingQuestionIds);
    Map<UUID, Question> storedById = new HashMap<>();
    for (Question q : existing.questions()) {
      storedById.put(q.id(), q);
    }
    for (CreatePollCommand.QuestionUpdate qu : incoming) {
      Question stored = resolveVotedStored(qu, storedById, counts);
      if (stored == null) {
        continue;
      }
      lockArityChange(stored, qu);
      lockOptionDelete(stored, qu);
    }
  }

  private static Set<UUID> collectIncomingQuestionIds(
      List<CreatePollCommand.QuestionUpdate> incoming) {
    Set<UUID> ids = new HashSet<>();
    for (CreatePollCommand.QuestionUpdate qu : incoming) {
      if (qu.id() != null) {
        ids.add(qu.id());
      }
    }
    return ids;
  }

  /** Question-delete: any stored question with voteCount>0 missing from the payload. */
  private static void lockQuestionDelete(
      Poll existing, Map<UUID, Long> counts, Set<UUID> incomingQuestionIds) {
    for (Question stored : existing.questions()) {
      long voteCount = counts.getOrDefault(stored.id(), 0L);
      if (voteCount > 0 && !incomingQuestionIds.contains(stored.id())) {
        throw new ResourceHasVotesException("QUESTION", stored.id());
      }
    }
  }

  /**
   * Returns the stored counterpart of {@code qu} if it carries votes and is structurally relevant
   * (known id, voteCount>0); {@code null} when the update should be ignored by the lock checks.
   */
  private static Question resolveVotedStored(
      CreatePollCommand.QuestionUpdate qu, Map<UUID, Question> storedById, Map<UUID, Long> counts) {
    if (qu.id() == null) {
      return null; // a brand-new question can't carry votes
    }
    Question stored = storedById.get(qu.id());
    if (stored == null) {
      return null; // unknown id — replaceQuestions will treat it as a new question
    }
    long voteCount = counts.getOrDefault(stored.id(), 0L);
    if (voteCount == 0) {
      return null;
    }
    return stored;
  }

  /** Arity change on a voted question is a structural mutation. */
  private static void lockArityChange(Question stored, CreatePollCommand.QuestionUpdate qu) {
    if (qu.minSelections() != stored.minSelections()
        || qu.maxSelections() != stored.maxSelections()) {
      throw new ResourceHasVotesException("QUESTION", stored.id());
    }
  }

  /** Option-delete: any stored option id not present in the incoming option list. */
  private static void lockOptionDelete(Question stored, CreatePollCommand.QuestionUpdate qu) {
    Set<UUID> incomingOptionIds = new HashSet<>();
    for (CreatePollCommand.OptionUpdate ou : qu.options()) {
      if (ou.id() != null) {
        incomingOptionIds.add(ou.id());
      }
    }
    for (Option storedOpt : stored.options()) {
      if (!incomingOptionIds.contains(storedOpt.id())) {
        throw new ResourceHasVotesException("OPTION", storedOpt.id());
      }
    }
  }

  @Transactional
  public void deleteForOwner(UUID pollId, String ownerUsername) {
    self.get().getForOwner(pollId, ownerUsername);
    repository.delete(pollId);
  }

  @Transactional
  public Poll clearVotesForOwner(UUID pollId, String ownerUsername) {
    self.get().getForOwner(pollId, ownerUsername);
    voteRepository.deleteForPoll(pollId);
    Poll after = repository.resetQuestionsToDraft(pollId);
    events.fire(new PollVotesClearedEvent(pollId, Instant.now()));
    return after;
  }

  /**
   * Duplicate a poll into a new aggregate owned by the same presenter. Title becomes "Copy of
   * <title>"; slug is derived from the new title via {@code resolveSlug} (so the result never
   * clashes with an existing slug). All questions/options are recreated with fresh UUIDs, leaving
   * the source poll untouched. Active question state is NOT carried over — the clone starts as
   * DRAFT.
   */
  @Transactional
  public Poll cloneForOwner(UUID pollId, String ownerUsername) {
    Poll src = self.get().getForOwner(pollId, ownerUsername);
    List<CreatePollCommand.QuestionDraft> drafts = new ArrayList<>(src.questions().size());
    for (Question q : src.questions()) {
      List<CreatePollCommand.OptionDraft> opts = new ArrayList<>(q.options().size());
      for (Option o : q.options()) {
        opts.add(new CreatePollCommand.OptionDraft(o.label()));
      }
      drafts.add(new CreatePollCommand.QuestionDraft(q.prompt(), opts));
    }
    return self.get()
        .create(
            ownerUsername,
            new CreatePollCommand("Copy of " + src.title(), null, drafts, src.allowedOrigins()));
  }

  @Transactional
  public Poll activateQuestionForOwner(UUID pollId, String ownerUsername, UUID questionId) {
    self.get().getForOwner(pollId, ownerUsername);
    return self.get().activateQuestion(pollId, questionId);
  }

  @Transactional
  public Poll closeActiveQuestionForOwner(UUID pollId, String ownerUsername) {
    Poll before = self.get().getForOwner(pollId, ownerUsername);
    UUID wasActive = before.activeQuestionId();
    Poll after = repository.closeActiveQuestion(pollId);
    if (wasActive != null) {
      events.fire(new PollQuestionClosedEvent(pollId, wasActive, Instant.now()));
    }
    return after;
  }

  /**
   * Close path that skips the ownership check — intended for the deck. The {@code
   * DeckTokenAuthenticationFilter} has already validated the token/poll binding by the time we get
   * here. Idempotent: closing when no question is ACTIVE is a no-op.
   */
  @Transactional
  public Poll closeActiveQuestion(UUID pollId) {
    return closeActiveQuestion(pollId, null);
  }

  /**
   * Same as {@link #closeActiveQuestion(UUID)} but conditional: if {@code expectedQuestionId} is
   * non-null and does not match the currently-active question, the close is a no-op. Lets the deck
   * scope its slide-leave close to the question that slide opened, so a slow close request can't
   * race past the next slide's activate and clobber it.
   */
  @Transactional
  public Poll closeActiveQuestion(UUID pollId, UUID expectedQuestionId) {
    Poll before =
        repository.findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
    UUID wasActive = before.activeQuestionId();
    if (expectedQuestionId != null && !expectedQuestionId.equals(wasActive)) {
      return before;
    }
    Poll after = repository.closeActiveQuestion(pollId);
    if (wasActive != null) {
      events.fire(new PollQuestionClosedEvent(pollId, wasActive, Instant.now()));
    }
    return after;
  }

  /**
   * Activation path that skips the ownership check — intended for callers that have already
   * authorised by some other mechanism (the deck-token filter on {@code /api/deck/**}). FR-004
   * atomicity and the ≥2-option precondition are still enforced here.
   */
  @Transactional
  public Poll activateQuestion(UUID pollId, UUID questionId) {
    Poll poll =
        repository.findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
    Question target =
        poll.questions().stream()
            .filter(q -> q.id().equals(questionId))
            .findFirst()
            .orElseThrow(
                () -> new NotFoundException("question " + questionId + " not in poll " + pollId));
    if (target.options() == null || target.options().size() < 2) {
      throw new ActivationRejectedException(
          "question " + questionId + " needs at least two options to activate");
    }
    // Idempotent-reactivate: if the target is already ACTIVE, short-circuit without touching the
    // storage layer so the deck-driven activation flow ({@code @TS-052}) does not rotate
    // activated_at or re-fire a snapshot event on a page remount.
    if (questionId.equals(poll.activeQuestionId())) {
      return poll;
    }
    Poll after = repository.activateQuestion(pollId, questionId);
    events.fire(new PollActiveQuestionChangedEvent(pollId, questionId, Instant.now()));
    return after;
  }

  private String resolveSlug(String requested, String title, UUID excludingPollId) {
    String candidate = requested != null ? requested : SlugDeriver.deriveFromTitle(title);
    // Reserved check wins over format: `j` is on the reserved list (it's the intended
    // short "join" path) yet fails SlugValidator's 3-40 length rule. @TS-012's Example
    // table asserts `j` surfaces as SLUG_RESERVED, not SLUG_INVALID, so the reserved
    // lookup runs first. ReservedSlugs.isReserved null-guards, so a null candidate
    // falls through to the format branch where SLUG_INVALID is the correct code.
    if (ReservedSlugs.isReserved(candidate)) {
      throw new SlugReservedException(candidate);
    }
    if (!SlugValidator.isValidFormat(candidate)) {
      throw new SlugInvalidException(candidate == null ? "" : candidate);
    }
    if (repository.slugTaken(candidate, excludingPollId)) {
      throw new SlugTakenException(candidate);
    }
    return candidate;
  }

  private void requireOwner(Poll poll, String ownerUsername) {
    if (!poll.ownerUsername().equals(ownerUsername)) {
      throw new NotOwnerException("poll " + poll.id() + " is not owned by " + ownerUsername);
    }
  }

  private List<Question> buildDraftQuestions(
      UUID pollId, List<CreatePollCommand.QuestionDraft> drafts) {
    if (drafts == null || drafts.isEmpty()) {
      return List.of();
    }
    List<Question> out = new ArrayList<>(drafts.size());
    for (int i = 0; i < drafts.size(); i++) {
      CreatePollCommand.QuestionDraft draft = drafts.get(i);
      UUID qid = UUID.randomUUID();
      List<Option> options = new ArrayList<>(draft.options().size());
      for (int j = 0; j < draft.options().size(); j++) {
        options.add(new Option(UUID.randomUUID(), qid, draft.options().get(j).label(), j));
      }
      out.add(
          new Question(
              qid,
              pollId,
              draft.prompt(),
              i,
              QuestionStatus.DRAFT,
              draft.minSelections(),
              draft.maxSelections(),
              options,
              null,
              null));
    }
    return out;
  }
}
