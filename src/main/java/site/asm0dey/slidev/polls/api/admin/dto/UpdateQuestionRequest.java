package site.asm0dey.slidev.polls.api.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;

/**
 * Update-path question payload. {@code id} is the existing question UUID for in-place edits; absent
 * / null for newly-added questions. {@code options[].id} obeys the same convention. The service
 * uses these ids to diff against current state — matched ids keep their row (and any vote history
 * bound to it); unmatched ids in the existing row set are deleted (cascading their votes).
 */
public record UpdateQuestionRequest(
    UUID id,
    @NotBlank @Size(min = 1, max = 500) String prompt,
    @Valid @Size(min = 2) List<OptionUpdateBody> options) {

  public record OptionUpdateBody(UUID id, @NotBlank @Size(min = 1, max = 200) String label) {}

  public CreatePollCommand.QuestionUpdate toUpdate() {
    List<CreatePollCommand.OptionUpdate> opts = new ArrayList<>();
    if (options != null) {
      for (OptionUpdateBody body : options) {
        opts.add(new CreatePollCommand.OptionUpdate(body.id(), body.label()));
      }
    }
    return new CreatePollCommand.QuestionUpdate(id, prompt, opts);
  }
}
