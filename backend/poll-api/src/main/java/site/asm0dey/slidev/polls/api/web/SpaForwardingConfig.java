package site.asm0dey.slidev.polls.api.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Server-side routing for the two SPA shells that share the poll-api origin.
 *
 * <ul>
 *   <li>{@code /} and every single-segment kebab-case slug ({@code /{slug}}) forward to {@code
 *       /index.html} — the voter SPA's Vue Router reads the slug client-side and hydrates the right
 *       view.
 *   <li>{@code /admin}, {@code /admin/}, and every path under {@code /admin/} forward to {@code
 *       /admin/index.html} — the backoffice SPA is deployed under the {@code /admin/} base so its
 *       Vue Router expects to own that subtree.
 * </ul>
 *
 * <p>What this controller deliberately does NOT touch:
 *
 * <ul>
 *   <li>{@code /api/**} — all backend routes live under {@code @RestController}
 *       {@code @RequestMapping} entries and are matched before view-returning controllers (Spring's
 *       most- specific-wins dispatch). {@code /api/admin/**} in particular stays gated by {@link
 *       site.asm0dey.slidev.polls.api.security.SecurityConfig}; this controller never sees those
 *       requests.
 *   <li>Static assets under {@code /assets/**}, {@code /admin/assets/**}, etc. — the SPA regex
 *       {@code [a-z0-9-]{3,40}} matches a single segment, so a multi-segment asset URL falls
 *       through to Spring Boot's default static resource handler.
 * </ul>
 *
 * <p>Covers {@code @TS-043} (voter catch-all does not swallow {@code /api/polls/by-slug/{slug}})
 * and {@code @TS-044} (backoffice shell served, data routes still 401).
 */
@Controller
public class SpaForwardingConfig {

  // Voter shell — the SPA will read the slug from window.location.pathname and resolve it via
  // /api/polls/by-slug/{slug} on its own. The regex is intentionally identical to the CHECK
  // constraint on polls.slug so a malformed slug (upper-case, leading hyphen, etc.) falls through
  // to Spring's default 404 handling instead of serving the voter shell for a URL that could
  // never be a poll.
  @GetMapping({"/", "/{slug:[a-z0-9-]{3,40}}"})
  public String voterShell() {
    return "forward:/index.html";
  }

  // Backoffice shell — one handler for the two no-sub-path entry points and one for every deeper
  // route so Vue Router under base=/admin/ can own its full subtree. {*sub} is Spring's path-
  // variable capture for "the rest of the URL"; an empty capture matches /admin/ exactly.
  @GetMapping({"/admin", "/admin/", "/admin/{*sub}"})
  public String backofficeShell() {
    return "forward:/admin/index.html";
  }
}
