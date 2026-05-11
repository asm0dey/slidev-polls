package site.asm0dey.slidev.polls.core.error;

/** Thrown when a lookup by id / slug / token returns no row the caller may see. */
public class NotFoundException extends RuntimeException {
  public NotFoundException(String message) {
    super(message);
  }
}
