package site.asm0dey.slidev.polls.core.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Poll;

/**
 * Presenter-authored poll lifecycle service. Concrete class (not interface) — there is a single
 * production implementation and {@code PollServiceTest} wires it against a fake repository.
 *
 * <p>Methods suffixed with {@code ForOwner} enforce ownership — anything but the owning presenter
 * surfaces {@link site.asm0dey.slidev.polls.core.error.NotOwnerException}. Deck-initiated
 * activation (FR-018) calls {@link #activateQuestion(java.util.UUID, java.util.UUID)} after the
 * {@code DeckTokenAuthenticationFilter} has already validated the token/poll scope.
 */
public class PollService {

  private final PollRepository repository;

  public PollService(PollRepository repository) {
    this.repository = repository;
  }

  public Poll create(String ownerUsername, CreatePollCommand command) {
    throw new UnsupportedOperationException("T053 pending");
  }

  public List<Poll> listForOwner(String ownerUsername) {
    throw new UnsupportedOperationException("T053 pending");
  }

  public Poll getForOwner(UUID pollId, String ownerUsername) {
    throw new UnsupportedOperationException("T053 pending");
  }

  public Poll updateForOwner(UUID pollId, String ownerUsername, UpdatePollCommand command) {
    throw new UnsupportedOperationException("T053 pending");
  }

  public Poll updateStyleForOwner(
      UUID pollId, String ownerUsername, Map<String, Object> style) {
    throw new UnsupportedOperationException("T053 pending");
  }

  public void deleteForOwner(UUID pollId, String ownerUsername) {
    throw new UnsupportedOperationException("T053 pending");
  }

  public Poll activateQuestionForOwner(UUID pollId, String ownerUsername, UUID questionId) {
    throw new UnsupportedOperationException("T053 pending");
  }

  public Poll closeActiveQuestionForOwner(UUID pollId, String ownerUsername) {
    throw new UnsupportedOperationException("T053 pending");
  }

  /** Pre-authorised path (deck addon): caller has already validated the deck-token/poll scope. */
  public Poll activateQuestion(UUID pollId, UUID questionId) {
    throw new UnsupportedOperationException("T053 pending");
  }
}
