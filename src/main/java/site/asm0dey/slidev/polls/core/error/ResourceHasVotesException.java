package site.asm0dey.slidev.polls.core.error;

import java.util.UUID;

/**
 * Thrown when a presenter attempts a structurally-destructive edit on a question that already
 * carries recorded votes (FR-013). Destructive edits include deleting the question or one of its
 * options, or changing its selection arity ({@code minSelections} / {@code maxSelections}).
 *
 * <p>The lock lives in {@code PollService.updateForOwner} so the rule is enforced regardless of
 * caller; the global exception handler maps this to {@code 409 RESOURCE_HAS_VOTES}.
 */
public class ResourceHasVotesException extends RuntimeException {
  private final UUID resourceId;
  private final String kind; // "QUESTION" or "OPTION"

  public ResourceHasVotesException(String kind, UUID id) {
    super(kind + " " + id + " has votes and cannot be modified");
    this.resourceId = id;
    this.kind = kind;
  }

  public UUID resourceId() {
    return resourceId;
  }

  public String kind() {
    return kind;
  }
}
