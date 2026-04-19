package site.asm0dey.slidev.polls.api.admin;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Computes the public base URL to embed in {@link site.asm0dey.slidev.polls.api.admin.dto.PollDto}
 * join links and in the QR payload. The host is taken from the inbound request (honouring the
 * {@code X-Forwarded-Host} / {@code X-Forwarded-Proto} headers that a reverse proxy would set), so
 * the presenter always sees a URL that matches what their audience would type.
 *
 * <p>Why compute from the request rather than from a config property: the voter SPA, the backoffice
 * SPA, and the backend live on a single origin (plan.md §Constraints), and the test environment
 * serves under an ephemeral {@code MockMvc} host. A property would need to be overridden per
 * environment; derivation from the request is correct in every environment for free.
 */
final class PublicUrlBase {

  private PublicUrlBase() {}

  static String of(HttpServletRequest request) {
    StringBuilder buf = new StringBuilder();
    String forwardedProto = request.getHeader("X-Forwarded-Proto");
    String scheme =
        forwardedProto != null && !forwardedProto.isBlank() ? forwardedProto : request.getScheme();
    buf.append(scheme).append("://");

    String forwardedHost = request.getHeader("X-Forwarded-Host");
    if (forwardedHost != null && !forwardedHost.isBlank()) {
      buf.append(forwardedHost);
    } else {
      buf.append(request.getServerName());
      int port = request.getServerPort();
      if (port > 0
          && !((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443))) {
        buf.append(':').append(port);
      }
    }

    String context = request.getContextPath();
    if (context != null && !context.isBlank() && !"/".equals(context)) {
      buf.append(context);
    }
    return buf.toString();
  }
}
