package site.asm0dey.slidev.polls.core.service;

import java.util.List;

/**
 * Service-layer command for {@link PollService#create}. Mirrors {@code CreatePollRequest} from
 * {@code openapi.yaml} but at a DTO-free, Jackson-free level so {@code poll-core} stays
 * web-agnostic (Principle V).
 *
 * <p>{@code slug} is optional — when {@code null} the service derives one from {@code title} per
 * {@code @TS-010}. {@code allowedOrigins} is normalised to an empty list when null.
 */
public record CreatePollCommand(
    String title, String slug, List<QuestionDraft> questions, List<String> allowedOrigins) {

  public CreatePollCommand {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }

  public record QuestionDraft(
      String prompt, int minSelections, int maxSelections, List<OptionDraft> options) {

    public QuestionDraft {
      if (maxSelections < 1) throw new IllegalArgumentException("maxSelections must be ≥ 1");
      if (minSelections < 0) throw new IllegalArgumentException("minSelections must be ≥ 0");
      if (minSelections > maxSelections) {
        throw new IllegalArgumentException(
            "minSelections > maxSelections: " + minSelections + " > " + maxSelections);
      }
    }

    /** Convenience for tests + back-compat with callers that pre-date the per-question arity. */
    public QuestionDraft(String prompt, List<OptionDraft> options) {
      this(prompt, 1, 1, options);
    }
  }

  public record OptionDraft(String label) {}

  /** Update-path counterpart of {@link QuestionDraft}. {@code id} is null for new questions. */
  public record QuestionUpdate(
      java.util.UUID id,
      String prompt,
      int minSelections,
      int maxSelections,
      List<OptionUpdate> options) {

    public QuestionUpdate {
      if (maxSelections < 1) throw new IllegalArgumentException("maxSelections must be ≥ 1");
      if (minSelections < 0) throw new IllegalArgumentException("minSelections must be ≥ 0");
      if (minSelections > maxSelections) {
        throw new IllegalArgumentException("min > max");
      }
      options = options == null ? List.of() : List.copyOf(options);
    }

    /** Convenience for tests + back-compat with callers that pre-date the per-question arity. */
    public QuestionUpdate(java.util.UUID id, String prompt, List<OptionUpdate> options) {
      this(id, prompt, 1, 1, options);
    }
  }

  /** Update-path counterpart of {@link OptionDraft}. {@code id} is null for new options. */
  public record OptionUpdate(java.util.UUID id, String label) {}
}
