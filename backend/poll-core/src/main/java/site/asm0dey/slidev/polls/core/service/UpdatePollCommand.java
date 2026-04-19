package site.asm0dey.slidev.polls.core.service;

import java.util.List;

/**
 * Patch-style command for {@link PollService#update}. Any field may be {@code null} to leave it
 * unchanged. {@code questions} — when non-null — replaces the full question list (per OpenAPI
 * {@code UpdatePollRequest}).
 */
public record UpdatePollCommand(
    String title, String slug, List<CreatePollCommand.QuestionDraft> questions) {}
