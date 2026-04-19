package site.asm0dey.slidev.polls.core.error;

/**
 * Thrown when a deck activation request arrives with a token that is unknown, revoked, or whose
 * hash does not match any persisted row (FR-019, @TS-055).
 */
public class DeckTokenInvalidException extends RuntimeException {
  public DeckTokenInvalidException(String message) {
    super(message);
  }
}
