package site.asm0dey.slidev.polls.core.error;

/**
 * Thrown when a voter whose token has already been recorded against a question attempts to submit a
 * second vote for the same question (FR-009, @TS-023).
 */
public class AlreadyVotedException extends RuntimeException {
  public AlreadyVotedException(String message) {
    super(message);
  }
}
