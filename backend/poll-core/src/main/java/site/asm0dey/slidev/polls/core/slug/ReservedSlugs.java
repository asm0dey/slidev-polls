package site.asm0dey.slidev.polls.core.slug;

import java.util.Set;

public final class ReservedSlugs {

  private static final Set<String> RESERVED =
      Set.of("admin", "api", "assets", "static", "j", "login", "logout");

  private ReservedSlugs() {}

  public static boolean isReserved(String slug) {
    // Empty / null slugs are rejected by SlugValidator on length grounds before
    // this check runs; surfacing them as "reserved" here would make error
    // reporting lie about which check failed (SLUG_RESERVED vs SLUG_INVALID).
    if (slug == null || slug.isEmpty()) {
      return false;
    }
    return RESERVED.contains(slug.toLowerCase());
  }
}
