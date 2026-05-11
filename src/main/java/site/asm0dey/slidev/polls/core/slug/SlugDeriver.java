package site.asm0dey.slidev.polls.core.slug;

import java.text.Normalizer;

/**
 * Derives a kebab-case slug from a human-authored poll title (FR-005, {@code @TS-010}). The output
 * is guaranteed to match {@link SlugValidator#PATTERN} so it can round-trip through the same
 * validator the API applies to presenter-supplied slugs. If the title has no slug-worthy characters
 * at all, the caller is expected to treat that as a validation failure upstream — deriveFromTitle
 * returns {@code null} in that case rather than inventing an identifier.
 */
public final class SlugDeriver {

  private SlugDeriver() {}

  public static String deriveFromTitle(String title) {
    if (title == null) {
      return null;
    }
    String normalized = Normalizer.normalize(title, Normalizer.Form.NFKD);
    StringBuilder out = new StringBuilder(normalized.length());
    boolean dashPending = false;
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        c = (char) (c + 32);
      }
      boolean alnum = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
      if (alnum) {
        if (dashPending && !out.isEmpty()) {
          out.append('-');
        }
        out.append(c);
        dashPending = false;
      } else {
        dashPending = true;
      }
    }
    if (out.isEmpty()) {
      return null;
    }
    if (out.length() > SlugValidator.MAX_LENGTH) {
      out.setLength(SlugValidator.MAX_LENGTH);
      // Dropping mid-token may leave a trailing dash — shouldn't happen given
      // our accumulator, but guard anyway to keep the post-condition tight.
      while (!out.isEmpty() && out.charAt(out.length() - 1) == '-') {
        out.setLength(out.length() - 1);
      }
    }
    return out.toString();
  }
}
