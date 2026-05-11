package site.asm0dey.slidev.polls.core.error;

/**
 * Thrown when a presenter asks to activate a question that does not meet the pre-activation
 * contract — e.g., fewer than two options (FR-004). CLOSED questions are reopenable; only
 * structural pre-conditions trigger this exception now.
 */
public class ActivationRejectedException extends RuntimeException {
  public ActivationRejectedException(String message) {
    super(message);
  }
}
