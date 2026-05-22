package site.asm0dey.slidev.polls.core.origin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import site.asm0dey.slidev.polls.core.error.InvalidOriginException;

public final class OriginNormaliser {
  private OriginNormaliser() {}

  public static List<String> normalise(List<String> raw) {
    return raw.stream().map(OriginNormaliser::normaliseOne).distinct().toList();
  }

  static String normaliseOne(String input) {
    if (input == null || input.isBlank()) throw new InvalidOriginException(String.valueOf(input));
    URI uri;
    try {
      uri = new URI(input.trim());
    } catch (URISyntaxException ignored) {
      throw new InvalidOriginException(input);
    }
    String rawScheme = uri.getScheme();
    String host = uri.getHost();
    int port = uri.getPort();
    if (rawScheme == null || host == null) throw new InvalidOriginException(input);
    String scheme = rawScheme.toLowerCase();
    if (!scheme.equals("http") && !scheme.equals("https")) throw new InvalidOriginException(input);
    String s = scheme + "://" + host.toLowerCase();
    if (port != -1) s += ":" + port;
    return s;
  }
}
