package site.asm0dey.slidev.polls.core.slug;

import java.util.Set;

public final class ReservedSlugs {

  private static final Set<String> RESERVED =
      Set.of("admin", "api", "assets", "static", "j", "login", "logout");

  private ReservedSlugs() {}

  public static boolean isReserved(String slug) {
    if (slug == null || slug.isEmpty()) {
      return true;
    }
    return RESERVED.contains(slug.toLowerCase());
  }
}
