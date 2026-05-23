package site.asm0dey.slidev.polls.core.error;

/** Thrown when a block is disallowed (blocking yourself or the bootstrap administrator). */
public class CannotBlockException extends RuntimeException {
  public CannotBlockException(String message) {
    super(message);
  }
}
