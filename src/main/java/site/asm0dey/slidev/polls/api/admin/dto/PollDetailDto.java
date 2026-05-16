package site.asm0dey.slidev.polls.api.admin.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;

/**
 * Full poll view returned by {@code GET/POST/PATCH /api/admin/polls/{pollId}}. Mirrors the {@code
 * PollDetail} schema (Poll + questions) in {@code openapi.yaml}.
 *
 * <p>{@code voteCounts} is a side-channel into {@link QuestionDto} so the admin response carries
 * the per-question ballot count without forcing the domain {@code Question} to know about votes.
 * Callers without the count (freshly-created polls, list views) pass {@code Map.of()} and every
 * question simply reports zero.
 */
public record PollDetailDto(
    UUID id,
    String title,
    String slug,
    PollStatus status,
    String publicUrl,
    UUID activeQuestionId,
    List<QuestionDto> questions,
    List<String> allowedOrigins) {

  public static PollDetailDto from(
      Poll domain, String publicUrlBase, Map<UUID, Long> voteCounts) {
    PollDto summary = PollDto.from(domain, publicUrlBase);
    List<QuestionDto> questions =
        domain.questions().stream()
            .map(q -> QuestionDto.from(q, voteCounts.getOrDefault(q.id(), 0L).intValue()))
            .toList();
    return new PollDetailDto(
        summary.id(),
        summary.title(),
        summary.slug(),
        summary.status(),
        summary.publicUrl(),
        summary.activeQuestionId(),
        questions,
        domain.allowedOrigins());
  }

  /** Convenience for callers that don't yet have vote counts — every question reports {@code 0}. */
  public static PollDetailDto from(Poll domain, String publicUrlBase) {
    return from(domain, publicUrlBase, Map.of());
  }
}
