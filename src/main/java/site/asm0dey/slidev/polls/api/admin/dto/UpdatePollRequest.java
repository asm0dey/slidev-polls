package site.asm0dey.slidev.polls.api.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;
import site.asm0dey.slidev.polls.core.service.UpdatePollCommand;

/**
 * Patch-style request body for {@code PATCH /api/admin/polls/{pollId}}. Any field may be omitted;
 * only present fields are applied. When {@code questions} is present it replaces the entire list
 * (per the OpenAPI contract). Mirrors {@code UpdatePollRequest} in {@code openapi.yaml}.
 */
public record UpdatePollRequest(
    @Size(min = 1, max = 200) String title,
    // See CreatePollRequest: slug validation lives in PollService so every 409 carries a
    // slug-specific ProblemCode. A @Size here would leak out as 400 VALIDATION_FAILED.
    String slug,
    @Valid List<UpdateQuestionRequest> questions,
    @Size(max = 32) List<String> allowedOrigins) {

  public UpdatePollCommand toCommand() {
    List<CreatePollCommand.QuestionUpdate> updates = null;
    if (questions != null) {
      updates = new ArrayList<>();
      for (UpdateQuestionRequest q : questions) {
        updates.add(q.toUpdate());
      }
    }
    return new UpdatePollCommand(title, slug, updates, allowedOrigins);
  }
}
