package site.asm0dey.slidev.polls.core.error;

/**
 * Thrown when an authenticated presenter attempts to read or mutate a poll owned by a different
 * presenter. Distinct from {@link NotFoundException} so callers can still confirm the poll exists
 * for debugging, without revealing its contents.
 */
public class NotOwnerException extends RuntimeException {
  public NotOwnerException(String message) {
    super(message);
  }
}
