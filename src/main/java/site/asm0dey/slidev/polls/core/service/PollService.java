package site.asm0dey.slidev.polls.core.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.error.ActivationRejectedException;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.error.NotOwnerException;
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
@Service
public class PollService {

  private final PollRepository repository;
  private final ApplicationEventPublisher events;
  // Spring 6 rejects circular references by default and Spring AOT cannot generate code
  // for an `@Lazy` self-injected setter (the AOT-emitted CGLIB proxy clashes with
  // SerializableNoOp at setCallbacks). ObjectProvider resolves on demand from the bean
  // factory, which returns the proxy that `@Transactional` advice is bound to, so
  // self-invocations still cross a transactional boundary.
  private final ObjectProvider<PollService> self;
  private final VoteRepository voteRepository;

  public PollService(
      PollRepository repository,
      ApplicationEventPublisher events,
      ObjectProvider<PollService> self,
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
            command.style() == null ? Map.of() : command.style(),
            null,
            questions,
            origins,
            now,
            now);
    return repository.insert(poll);
  }

  @Transactional(readOnly = true)
  public List<Poll> listForOwner(String ownerUsername) {
    return repository.findByOwner(ownerUsername);
  }

  @Transactional(readOnly = true)
  public Poll getForOwner(UUID pollId, String ownerUsername) {
    Poll poll =
        repository.findById(pollId).orElseThrow(() -> new NotFoundException(pollId.toString()));
    requireOwner(poll, ownerUsername);
    return poll;
  }

  @Transactional
  public Poll updateForOwner(UUID pollId, String ownerUsername, UpdatePollCommand command) {
    Poll existing = self.getObject().getForOwner(pollId, ownerUsername);
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
      return repository.replaceQuestions(pollId, command.questions());
    }
    return afterHeader;
  }

  @Transactional
  public Poll updateStyleForOwner(UUID pollId, String ownerUsername, Map<String, Object> style) {
    self.getObject().getForOwner(pollId, ownerUsername);
    return repository.updateStyle(pollId, style == null ? Map.of() : style);
  }

  @Transactional
  public void deleteForOwner(UUID pollId, String ownerUsername) {
    self.getObject().getForOwner(pollId, ownerUsername);
    repository.delete(pollId);
  }

  @Transactional
  public Poll clearVotesForOwner(UUID pollId, String ownerUsername) {
    self.getObject().getForOwner(pollId, ownerUsername);
    voteRepository.deleteForPoll(pollId);
    Poll after = repository.resetQuestionsToDraft(pollId);
    events.publishEvent(new PollVotesClearedEvent(pollId, Instant.now()));
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
    Poll src = self.getObject().getForOwner(pollId, ownerUsername);
    List<CreatePollCommand.QuestionDraft> drafts = new ArrayList<>(src.questions().size());
    for (Question q : src.questions()) {
      List<CreatePollCommand.OptionDraft> opts = new ArrayList<>(q.options().size());
      for (Option o : q.options()) {
        opts.add(new CreatePollCommand.OptionDraft(o.label()));
      }
      drafts.add(new CreatePollCommand.QuestionDraft(q.prompt(), opts));
    }
    return self.getObject()
        .create(
            ownerUsername,
            new CreatePollCommand(
                "Copy of " + src.title(), null, src.style(), drafts, src.allowedOrigins()));
  }

  @Transactional
  public Poll activateQuestionForOwner(UUID pollId, String ownerUsername, UUID questionId) {
    self.getObject().getForOwner(pollId, ownerUsername);
    return self.getObject().activateQuestion(pollId, questionId);
  }

  @Transactional
  public Poll closeActiveQuestionForOwner(UUID pollId, String ownerUsername) {
    Poll before = self.getObject().getForOwner(pollId, ownerUsername);
    UUID wasActive = before.activeQuestionId();
    Poll after = repository.closeActiveQuestion(pollId);
    if (wasActive != null) {
      events.publishEvent(new PollQuestionClosedEvent(pollId, wasActive, Instant.now()));
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
    events.publishEvent(new PollActiveQuestionChangedEvent(pollId, questionId, Instant.now()));
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
          new Question(qid, pollId, draft.prompt(), i, QuestionStatus.DRAFT, options, null, null));
    }
    return out;
  }
}
