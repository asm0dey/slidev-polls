package site.asm0dey.slidev.polls.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.error.NotFoundException;

/**
 * Drives the per-question ballot arity contract on {@link VoteService#recordVote}. Validates that
 * single-choice questions reject ballots of size ≠ 1, multi-choice questions honor the [min, max]
 * range, abstain (size 0) is gated by minSelections == 0, and ballot-level invariants (no duplicate
 * options, every option belongs to the active question) trip before the repository is touched.
 */
class VoteServiceArityTest {

  @Test
  void singleChoiceRequiresExactlyOne() {
    Fixture f = fixture(/* min */ 1, /* max */ 1, /* options */ 2);
    assertThatThrownBy(
            () ->
                f.service.recordVote("p", List.of(f.options.getFirst(), f.options.getLast()), "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 1");
  }

  @Test
  void multiAllowsRange() {
    Fixture f = fixture(0, 3, 3);
    when(f.repo.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    f.service.recordVote("p", List.of(f.options.getFirst(), f.options.get(2)), "v");
    verify(f.events).publishEvent((Object) any());
  }

  @Test
  void abstainAcceptedWhenMinZero() {
    Fixture f = fixture(0, 3, 3);
    when(f.repo.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Vote stored = f.service.recordVote("p", List.of(), "v");

    assertThat(stored.optionIds()).isEmpty();
    ArgumentCaptor<Vote> inserted = ArgumentCaptor.forClass(Vote.class);
    verify(f.repo).insert(inserted.capture());
    assertThat(inserted.getValue().optionIds()).isEmpty();
    verify(f.events).publishEvent((Object) any());
  }

  @Test
  void abstainRejectedWhenMinPositive() {
    Fixture f = fixture(1, 3, 3);
    assertThatThrownBy(() -> f.service.recordVote("p", List.of(), "v"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ballotAboveMaxRejected() {
    Fixture f = fixture(0, 2, 3);
    assertThatThrownBy(
            () ->
                f.service.recordVote(
                    "p", List.of(f.options.getFirst(), f.options.get(1), f.options.getLast()), "v"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void duplicateOptionInBallotRejected() {
    Fixture f = fixture(0, 3, 3);
    assertThatThrownBy(
            () ->
                f.service.recordVote("p", List.of(f.options.getFirst(), f.options.getFirst()), "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void unknownOptionRejected() {
    Fixture f = fixture(0, 3, 3);
    assertThatThrownBy(() -> f.service.recordVote("p", List.of(UUID.randomUUID()), "v"))
        .isInstanceOf(NotFoundException.class);
  }

  // ----- fixture --------------------------------------------------------------

  private static Fixture fixture(int min, int max, int optionCount) {
    UUID pollId = UUID.randomUUID();
    UUID questionId = UUID.randomUUID();
    List<UUID> optionIds = new ArrayList<>(optionCount);
    List<Option> options = new ArrayList<>(optionCount);
    for (int i = 0; i < optionCount; i++) {
      UUID oid = UUID.randomUUID();
      optionIds.add(oid);
      options.add(new Option(oid, questionId, "opt-" + i, i));
    }
    Question question =
        new Question(
            questionId,
            pollId,
            "Q?",
            0,
            QuestionStatus.ACTIVE,
            min,
            max,
            options,
            Instant.now(),
            null);
    Poll poll =
        new Poll(
            pollId,
            "alice",
            "T",
            "p",
            PollStatus.OPEN,
            questionId,
            List.of(question),
            List.of(),
            Instant.now(),
            Instant.now());

    PollRepository pollRepo = mock(PollRepository.class);
    when(pollRepo.findBySlug("p")).thenReturn(Optional.of(poll));

    VoteRepository voteRepo = mock(VoteRepository.class);
    when(voteRepo.tally(any())).thenReturn(Map.of());

    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    VoteService service = new VoteService(pollRepo, voteRepo, events);
    return new Fixture(service, voteRepo, events, optionIds);
  }

  private record Fixture(
      VoteService service,
      VoteRepository repo,
      ApplicationEventPublisher events,
      List<UUID> options) {}
}
