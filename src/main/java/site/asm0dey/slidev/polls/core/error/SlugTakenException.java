package site.asm0dey.slidev.polls.core.error;

/** Thrown when the requested slug is already owned by another poll (FR-005, @TS-013, @TS-014). */
public class SlugTakenException extends RuntimeException {
  public SlugTakenException(String slug) {
    super("slug already in use: " + slug);
  }
}
