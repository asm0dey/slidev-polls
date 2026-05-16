package site.asm0dey.slidev.polls.api.admin.dto;

import java.util.List;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;

/**
 * Mirrors the {@code Question} schema in {@code openapi.yaml}. {@code minSelections} / {@code
 * maxSelections} surface the per-question arity (single-choice questions report {@code (1, 1)});
 * {@code voteCount} is the number of cast ballots for this question — used by the backoffice to
 * lock structural edits once anyone has voted.
 */
public record QuestionDto(
    UUID id,
    String prompt,
    QuestionStatus status,
    int minSelections,
    int maxSelections,
    int voteCount,
    int ordinal,
    List<OptionDto> options) {

  public static QuestionDto from(Question question, int voteCount) {
    List<OptionDto> opts = question.options().stream().map(OptionDto::from).toList();
    return new QuestionDto(
        question.id(),
        question.prompt(),
        question.status(),
        question.minSelections(),
        question.maxSelections(),
        voteCount,
        question.ordinal(),
        opts);
  }
}
