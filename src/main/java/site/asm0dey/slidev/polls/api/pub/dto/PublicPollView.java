package site.asm0dey.slidev.polls.api.pub.dto;

import java.util.UUID;
import site.asm0dey.slidev.polls.api.admin.dto.QuestionDto;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.Question;

/**
 * Public-facing poll view returned by {@code GET /api/polls/by-slug/{slug}}. Mirrors the {@code
 * PublicPollView} schema in {@code openapi.yaml}: no ownership info, no audit columns, no
 * PII-shaped fields. {@code state} collapses the poll/question lifecycle down to what the voter
 * needs: {@code WAITING} (no active question) or {@code ACTIVE} (a question is live, included
 * inline).
 *
 * <p>{@code alreadyVoted} is a best-effort hint — {@code null} when the server has no {@code
 * sp_voter} cookie to look up, {@code true}/{@code false} once a cookie is present. The {@code
 * Question} DTO shape is reused from the admin surface since the OpenAPI schemas are identical.
 */
public record PublicPollView(
    UUID pollId,
    String slug,
    String title,
    State state,
    QuestionDto activeQuestion,
    Boolean alreadyVoted) {

  public enum State {
    WAITING,
    ACTIVE
  }

  public static PublicPollView from(Poll poll, Boolean alreadyVoted) {
    UUID activeQuestionId = poll.activeQuestionId();
    Question active = null;
    if (activeQuestionId != null) {
      active =
          poll.questions().stream()
              .filter(q -> q.id().equals(activeQuestionId))
              .findFirst()
              .orElse(null);
    }
    State state = active == null ? State.WAITING : State.ACTIVE;
    // Voter-facing view doesn't surface vote counts; default to 0 so the shared QuestionDto shape
    // still serialises.
    QuestionDto activeDto = active == null ? null : QuestionDto.from(active, 0);
    return new PublicPollView(poll.id(), poll.slug(), poll.title(), state, activeDto, alreadyVoted);
  }
}
