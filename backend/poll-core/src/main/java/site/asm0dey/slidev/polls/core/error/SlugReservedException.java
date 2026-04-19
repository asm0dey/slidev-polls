package site.asm0dey.slidev.polls.core.error;

/**
 * Thrown when a caller-supplied slug collides with one of the reserved route stems listed in {@code
 * ReservedSlugs} (FR-005, @TS-012).
 */
public class SlugReservedException extends RuntimeException {
  public SlugReservedException(String slug) {
    super("slug is reserved: " + slug);
  }
}
