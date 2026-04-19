package site.asm0dey.slidev.polls.core.service;

import java.util.List;
import java.util.Map;

/**
 * Service-layer command for {@link PollService#create}. Mirrors {@code CreatePollRequest} from
 * {@code openapi.yaml} but at a DTO-free, Jackson-free level so {@code poll-core} stays
 * web-agnostic (Principle V).
 *
 * <p>{@code slug} is optional — when {@code null} the service derives one from {@code title} per
 * {@code @TS-010}. {@code style} is a free-form theme map persisted as jsonb; {@code null} is
 * equivalent to an empty map.
 */
public record CreatePollCommand(
    String title, String slug, Map<String, Object> style, List<QuestionDraft> questions) {

  public record QuestionDraft(String prompt, List<OptionDraft> options) {}

  public record OptionDraft(String label) {}
}
