package site.asm0dey.slidev.polls.core.error;

/**
 * Thrown when a presenter asks to activate a question that does not meet the pre-activation
 * contract — e.g., fewer than two options (FR-004) or the question is already CLOSED.
 */
public class ActivationRejectedException extends RuntimeException {
  public ActivationRejectedException(String message) {
    super(message);
  }
}
