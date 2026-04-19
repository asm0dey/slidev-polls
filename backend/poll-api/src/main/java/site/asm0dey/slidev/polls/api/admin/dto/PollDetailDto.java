package site.asm0dey.slidev.polls.api.admin.dto;

import java.util.List;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;

/**
 * Full poll view returned by {@code GET/POST/PATCH /api/admin/polls/{pollId}}. Mirrors the {@code
 * PollDetail} schema (Poll + questions + style) in {@code openapi.yaml}.
 */
public record PollDetailDto(
    UUID id,
    String title,
    String slug,
    PollStatus status,
    String publicUrl,
    UUID activeQuestionId,
    PollStyleDto style,
    List<QuestionDto> questions) {

  public static PollDetailDto from(Poll domain, String publicUrlBase) {
    PollDto summary = PollDto.from(domain, publicUrlBase);
    List<QuestionDto> questions = domain.questions().stream().map(QuestionDto::from).toList();
    return new PollDetailDto(
        summary.id(),
        summary.title(),
        summary.slug(),
        summary.status(),
        summary.publicUrl(),
        summary.activeQuestionId(),
        PollStyleDto.fromMap(domain.style()),
        questions);
  }
}
