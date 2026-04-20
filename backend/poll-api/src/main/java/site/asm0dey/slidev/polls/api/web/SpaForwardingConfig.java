package site.asm0dey.slidev.polls.api.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Server-side routing for the two SPA shells that share the poll-api origin.
 *
 * <ul>
 *   <li>{@code /} and every single-segment kebab-case slug ({@code /{slug}}) forward to {@code
 *       /index.html} — the voter SPA's Vue Router reads the slug client-side and hydrates the right
 *       view. {@code /index.html} itself carries a dot, so the slug regex refuses to match it and
 *       the forwarded dispatch lands on the default static-resource handler instead of re-entering
 *       this controller.
 *   <li>{@code /admin/**} is served by a dedicated {@link PathResourceResolver} whose location is
 *       {@code classpath:/static/admin/}. It serves the real resource when one exists (the shell at
 *       {@code index.html}, every hashed asset under {@code assets/**}) and falls back to the shell
 *       for dot-less deep links (SPA routes like {@code /admin/polls/42}) so Vue Router under
 *       base={@code /admin/} can own its full subtree.
 * </ul>
 *
 * <p>Why a resource resolver instead of another {@code @GetMapping("/admin/{*sub}")} controller
 * method: a controller mapping matches the forward target {@code /admin/index.html} as {@code
 * sub=index.html}, so the re-dispatch re-enters the same handler and forwards forever ({@code
 * StackOverflowError} inside {@code ServletRequestWrapper.getRemoteAddr} — BUG-002). Spring's
 * {@code PathPattern} has no way to exclude dotted segments from {@code {*sub}}, so the fix is to
 * stop forwarding and serve the bytes directly.
 *
 * <p>What this configuration deliberately does NOT touch:
 *
 * <ul>
 *   <li>{@code /api/**} — all backend routes live under {@code @RestController} entries and are
 *       matched before view-returning controllers and static-resource handlers. {@code
 *       /api/admin/**} in particular stays gated by {@link
 *       site.asm0dey.slidev.polls.api.security.SecurityConfig}.
 *   <li>Voter static assets under {@code /assets/**} — the voter SPA regex {@code [a-z0-9-]{3,40}}
 *       matches a single segment, so a multi-segment asset URL falls through to Spring Boot's
 *       default {@code /**} resource handler backed by {@code classpath:/static/}.
 * </ul>
 *
 * <p>Covers {@code @TS-043} (voter catch-all does not swallow {@code /api/polls/by-slug/{slug}}),
 * {@code @TS-044} (backoffice shell served, data routes still 401), and {@code @BUG-002} (compose
 * stack frontends are reachable).
 */
@Configuration
@Controller
public class SpaForwardingConfig implements WebMvcConfigurer {

  // Voter shell — the SPA reads the slug from window.location.pathname and resolves it via
  // /api/polls/by-slug/{slug} on its own. The regex mirrors the polls.slug CHECK constraint so
  // a URL that could never be a real poll (upper-case, leading hyphen, dot-containing) falls
  // through to Spring's default 404 handling instead of serving the voter shell.
  @GetMapping({"/", "/{slug:[a-z0-9-]{3,40}}"})
  public String voterShell() {
    return "forward:/index.html";
  }

  // /admin (no trailing slash) — browsers land here when a user types "…/admin". Vue Router's
  // base is /admin/ so we need to land them at the slashed form or the SPA's asset-relative URLs
  // resolve against the wrong base. A plain redirect is cheaper than a forward and browser-
  // cacheable.
  @GetMapping("/admin")
  public String adminTrailingSlashRedirect() {
    return "redirect:/admin/";
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/admin/", "/admin/**")
        .addResourceLocations("classpath:/static/admin/")
        .resourceChain(false)
        .addResolver(new SpaShellFallbackResolver());
  }

  /**
   * Resolves a request under {@code /admin/**} to either the requested file or the SPA shell.
   *
   * <ul>
   *   <li>Empty or {@code "/"} path → {@code index.html} (the bare {@code /admin/} case).
   *   <li>Existing readable resource → that resource (the shell and every hashed asset).
   *   <li>Dot-less path that does not exist → {@code index.html} (SPA deep links like {@code
   *       /admin/polls/42} whose last segment carries no extension).
   *   <li>Dotted path that does not exist → {@code null} so Spring returns 404 (a missing asset
   *       should not masquerade as the shell with a 200, or the browser tries to execute HTML as
   *       JavaScript).
   * </ul>
   */
  private static final class SpaShellFallbackResolver extends PathResourceResolver {
    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      if (resourcePath.isEmpty() || "/".equals(resourcePath)) {
        return readable(location.createRelative("index.html"));
      }
      Resource direct = location.createRelative(resourcePath);
      if (direct.exists() && direct.isReadable()) {
        return direct;
      }
      if (!lastSegmentHasDot(resourcePath)) {
        return readable(location.createRelative("index.html"));
      }
      return null;
    }

    private static Resource readable(Resource r) throws IOException {
      return (r.exists() && r.isReadable()) ? r : null;
    }

    private static boolean lastSegmentHasDot(String path) {
      int lastSlash = path.lastIndexOf('/');
      String tail = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
      return tail.indexOf('.') >= 0;
    }
  }
}
