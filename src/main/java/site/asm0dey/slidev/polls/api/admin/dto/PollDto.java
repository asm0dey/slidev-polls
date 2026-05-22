package site.asm0dey.slidev.polls.api.admin.dto;

import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;

/**
 * Summary view of a poll returned by {@code GET /api/admin/polls}. Mirrors the {@code Poll} schema
 * in {@code openapi.yaml}.
 *
 * <p>{@code publicUrl} is the join link (a presenter-shareable URL pointing at the voter SPA for
 * this slug). The {@code publicUrlBase} argument on {@link #from} is the scheme+host prefix
 * computed at request time so the tests can assert on the full join-link shape without threading a
 * config property through every layer.
 */
public record PollDto(
    UUID id,
    String title,
    String slug,
    PollStatus status,
    String publicUrl,
    UUID activeQuestionId,
    boolean isOwner) {

  public static PollDto from(Poll domain, String publicUrlBase, boolean isOwner) {
    String base = publicUrlBase == null ? "" : stripTrailingSlash(publicUrlBase);
    return new PollDto(
        domain.id(),
        domain.title(),
        domain.slug(),
        domain.status(),
        base + "/" + domain.slug(),
        domain.activeQuestionId(),
        isOwner);
  }

  private static String stripTrailingSlash(String base) {
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }
}
