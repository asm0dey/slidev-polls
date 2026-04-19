package site.asm0dey.slidev.polls.core.error;

/**
 * Thrown when a caller-supplied slug fails the kebab-case / length contract enforced by {@code
 * SlugValidator} (FR-005, @TS-011).
 */
public class SlugInvalidException extends RuntimeException {
  public SlugInvalidException(String slug) {
    super("slug format is invalid: " + slug);
  }
}
