package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_QUESTIONS;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import site.asm0dey.slidev.polls.core.domain.Vote;
import site.asm0dey.slidev.polls.core.error.QuestionNotActiveException;

/**
 * Storage-level coverage for the status-gated retract path: {@code
 * VoteRepositoryImpl.deleteByQuestionAndVoter}. Run under both Postgres and H2 to confirm the
 * dialect-portable {@code DELETE … WHERE EXISTS (… status='ACTIVE')} idiom + the post-delete status
 * re-check behave identically.
 *
 * <p>Ported to {@code @QuarkusTest}: Postgres uses the application's injected {@code @Default}
 * {@link DSLContext} (Dev Services Postgres); H2 uses a self-contained raw-H2 {@link DSLContext}.
 * The {@code @Nested} engine subclasses were flattened into per-engine test methods.
 */
@QuarkusTest
class VoteRepositoryImplDeleteByVoterIT {

  @Inject DSLContext pgDsl;

  private static void seedOwner(DSLContext dsl) {
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, "retract-owner")
        .set(ADMIN_USER.PASSWORD_HASH, "n/a")
        .set(ADMIN_USER.CREATED_AT, OffsetDateTime.now())
        .onConflictDoNothing()
        .execute();
  }

  private static DSLContext h2() {
    return AbstractH2Test.dsl(AbstractH2Test.freshH2());
  }

  @Test
  void deletes_row_and_returns_option_id_when_question_active_postgres() {
    seedOwner(pgDsl);
    deletes_row_and_returns_option_id_when_question_active(pgDsl);
  }

  @Test
  void deletes_row_and_returns_option_id_when_question_active_h2() {
    DSLContext dsl = h2();
    seedOwner(dsl);
    deletes_row_and_returns_option_id_when_question_active(dsl);
  }

  private void deletes_row_and_returns_option_id_when_question_active(DSLContext dsl) {
    VoteRepositoryImpl voteRepository = new VoteRepositoryImpl(dsl);
    Poll seeded = seedActivatedPoll(dsl);
    UUID questionId = seeded.activeQuestionId();
    UUID optionA = seeded.questions().getFirst().options().getFirst().id();
    voteRepository.insert(
        new Vote(
            UUID.randomUUID(), seeded.id(), questionId, List.of(optionA), "v-1", Instant.now()));

    Optional<List<UUID>> deletedOption = voteRepository.deleteByQuestionAndVoter(questionId, "v-1");

    assertThat(deletedOption).contains(List.of(optionA));
    assertThat(voteRepository.alreadyVoted(questionId, "v-1")).isFalse();
  }

  @Test
  void returns_empty_when_voter_has_no_row_postgres() {
    seedOwner(pgDsl);
    returns_empty_when_voter_has_no_row(pgDsl);
  }

  @Test
  void returns_empty_when_voter_has_no_row_h2() {
    DSLContext dsl = h2();
    seedOwner(dsl);
    returns_empty_when_voter_has_no_row(dsl);
  }

  private void returns_empty_when_voter_has_no_row(DSLContext dsl) {
    VoteRepositoryImpl voteRepository = new VoteRepositoryImpl(dsl);
    Poll seeded = seedActivatedPoll(dsl);
    UUID questionId = seeded.activeQuestionId();
    UUID optionA = seeded.questions().getFirst().options().getFirst().id();
    voteRepository.insert(
        new Vote(
            UUID.randomUUID(), seeded.id(), questionId, List.of(optionA), "v-1", Instant.now()));

    Optional<List<UUID>> deletedOption =
        voteRepository.deleteByQuestionAndVoter(questionId, "v-other");

    assertThat(deletedOption).isEmpty();
    // Original row untouched.
    assertThat(voteRepository.alreadyVoted(questionId, "v-1")).isTrue();
  }

  @Test
  void throws_question_not_active_when_question_closed_postgres() {
    seedOwner(pgDsl);
    throws_question_not_active_when_question_closed(pgDsl);
  }

  @Test
  void throws_question_not_active_when_question_closed_h2() {
    DSLContext dsl = h2();
    seedOwner(dsl);
    throws_question_not_active_when_question_closed(dsl);
  }

  private void throws_question_not_active_when_question_closed(DSLContext dsl) {
    VoteRepositoryImpl voteRepository = new VoteRepositoryImpl(dsl);
    Poll seeded = seedActivatedPoll(dsl);
    UUID questionId = seeded.activeQuestionId();
    UUID optionA = seeded.questions().getFirst().options().getFirst().id();
    voteRepository.insert(
        new Vote(
            UUID.randomUUID(), seeded.id(), questionId, List.of(optionA), "v-1", Instant.now()));

    // Flip the question to CLOSED out of band; emulates the presenter closing while a retract
    // is in flight. The poll_questions_active_timestamp_ck constraint requires
    // activated_at IS NULL whenever status != 'ACTIVE', so mirror the production close path
    // (PollRepositoryImpl.deactivate/close — setNull(ACTIVATED_AT)) here.
    dsl.update(POLL_QUESTIONS)
        .set(POLL_QUESTIONS.STATUS, QuestionStatus.CLOSED.name())
        .setNull(POLL_QUESTIONS.ACTIVATED_AT)
        .where(POLL_QUESTIONS.ID.eq(questionId))
        .execute();

    assertThatThrownBy(() -> voteRepository.deleteByQuestionAndVoter(questionId, "v-1"))
        .isInstanceOf(QuestionNotActiveException.class);

    // Row stays — the status gate refused the delete.
    assertThat(voteRepository.alreadyVoted(questionId, "v-1")).isTrue();
  }

  private Poll seedActivatedPoll(DSLContext dsl) {
    PollRepositoryImpl pollRepository = new PollRepositoryImpl(dsl);
    UUID pollId = UUID.randomUUID();
    UUID q1 = UUID.randomUUID();
    List<Option> q1Options =
        new ArrayList<>(
            List.of(
                new Option(UUID.randomUUID(), q1, "A", 0),
                new Option(UUID.randomUUID(), q1, "B", 1)));
    Poll draft =
        new Poll(
            pollId,
            "retract-owner",
            "Retract poll",
            "retract-" + pollId.toString().substring(0, 8),
            PollStatus.DRAFT,
            null,
            List.of(
                new Question(
                    q1, pollId, "Q1?", 0, QuestionStatus.DRAFT, 1, 1, q1Options, null, null)),
            List.of(),
            null,
            null);
    pollRepository.insert(draft);
    return pollRepository.activateQuestion(pollId, q1);
  }
}
