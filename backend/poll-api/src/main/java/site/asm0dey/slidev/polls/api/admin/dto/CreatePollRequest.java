package site.asm0dey.slidev.polls.api.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;

/**
 * Request body for {@code POST /api/admin/polls}. Mirrors the {@code CreatePollRequest} schema in
 * {@code openapi.yaml}.
 *
 * <p>{@code slug} is optional; {@link site.asm0dey.slidev.polls.core.service.PollService#create}
 * derives a kebab-case slug from {@code title} when absent. Format/reserved/taken validation runs
 * server-side on both the derived and the supplied slug — the Bean Validation annotations here only
 * enforce the surface shape (length, JSON type), not the slug regex, so we can surface the same
 * {@code SLUG_INVALID} / {@code SLUG_RESERVED} / {@code SLUG_TAKEN} distinctions the OpenAPI
 * problem codes promise.
 */
public record CreatePollRequest(
    @NotBlank @Size(min = 1, max = 200) String title,
    // No Size constraint on slug: format, length, reserved, and uniqueness all funnel through
    // SlugValidator / ReservedSlugs / PollRepository inside PollService, and every violation
    // surfaces as a 409 with a slug-specific ProblemCode (@TS-011..@TS-014). A @Size here would
    // leak short/long slugs out as 400 VALIDATION_FAILED, which is the wrong wire contract.
    String slug,
    PollStyleDto style,
    @Valid @Size(min = 1) List<CreateQuestionRequest> questions) {

  public CreatePollCommand toCommand() {
    List<CreatePollCommand.QuestionDraft> drafts = new ArrayList<>();
    if (questions != null) {
      for (CreateQuestionRequest q : questions) {
        drafts.add(q.toDraft());
      }
    }
    return new CreatePollCommand(title, slug, style == null ? null : style.toMap(), drafts);
  }
}
