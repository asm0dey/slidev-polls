package site.asm0dey.slidev.polls.core.error;

/**
 * Thrown when a vote is submitted against a question whose status is not {@code ACTIVE} — either
 * because the presenter has already closed it, or because it has never been activated
 * (FR-010, @TS-025).
 */
public class QuestionNotActiveException extends RuntimeException {
  public QuestionNotActiveException(String message) {
    super(message);
  }
}
