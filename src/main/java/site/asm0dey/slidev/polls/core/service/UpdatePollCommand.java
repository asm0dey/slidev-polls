package site.asm0dey.slidev.polls.core.service;

import java.util.List;

/**
 * Patch-style command for {@link PollService#update}. Any field may be {@code null} to leave it
 * unchanged. {@code questions} — when non-null — replaces the full question list (per OpenAPI
 * {@code UpdatePollRequest}). {@code allowedOrigins} — when non-null — replaces the origins list;
 * {@code null} means "do not change origins".
 */
public record UpdatePollCommand(
    String title,
    String slug,
    List<CreatePollCommand.QuestionUpdate> questions,
    List<String> allowedOrigins) {

  public UpdatePollCommand {
    // null is a meaningful "unchanged" signal — preserve it; only copy when non-null
    allowedOrigins = allowedOrigins == null ? null : List.copyOf(allowedOrigins);
  }
}
