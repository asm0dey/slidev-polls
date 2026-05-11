package site.asm0dey.slidev.polls.api.admin.dto;

import java.util.List;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;

/** Mirrors the {@code Question} schema in {@code openapi.yaml}. */
public record QuestionDto(
    UUID id, String prompt, QuestionStatus status, int ordinal, List<OptionDto> options) {

  public static QuestionDto from(Question question) {
    List<OptionDto> opts = question.options().stream().map(OptionDto::from).toList();
    return new QuestionDto(
        question.id(), question.prompt(), question.status(), question.ordinal(), opts);
  }
}
