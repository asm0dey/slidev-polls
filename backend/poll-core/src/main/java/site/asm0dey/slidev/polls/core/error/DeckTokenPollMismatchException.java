package site.asm0dey.slidev.polls.core.error;

/**
 * Thrown when a deck token is valid but scoped to a different poll than the one the activation
 * request is targeting (@TS-054).
 */
public class DeckTokenPollMismatchException extends RuntimeException {
  public DeckTokenPollMismatchException(String message) {
    super(message);
  }
}
