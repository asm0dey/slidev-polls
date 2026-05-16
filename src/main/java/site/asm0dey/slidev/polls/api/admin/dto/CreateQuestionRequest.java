package site.asm0dey.slidev.polls.api.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;

/**
 * Request body element for creating / replacing a question. Mirrors {@code CreateQuestionRequest}
 * in {@code openapi.yaml}, including the ≥2-options minimum and the nested {@link OptionBody}
 * shape. {@code minSelections} / {@code maxSelections} are nullable on the wire so legacy clients
 * (which pre-date per-question arity) keep working — when omitted the draft defaults to {@code (1,
 * 1)} i.e. classic single-choice.
 */
public record CreateQuestionRequest(
    @NotBlank @Size(min = 1, max = 500) String prompt,
    Integer minSelections,
    Integer maxSelections,
    @Valid @Size(min = 2) List<OptionBody> options) {

  /** Option payload nested inside the request body (per the OpenAPI shape, not a domain option). */
  public record OptionBody(@NotBlank @Size(min = 1, max = 200) String label) {}

  /** Flattens into the service-layer draft type so {@code poll-core} stays Jackson-free. */
  public CreatePollCommand.QuestionDraft toDraft() {
    int min = minSelections == null ? 1 : minSelections;
    int max = maxSelections == null ? 1 : maxSelections;
    List<CreatePollCommand.OptionDraft> opts = new ArrayList<>();
    if (options != null) {
      for (OptionBody body : options) {
        opts.add(new CreatePollCommand.OptionDraft(body.label()));
      }
    }
    return new CreatePollCommand.QuestionDraft(prompt, min, max, opts);
  }
}
