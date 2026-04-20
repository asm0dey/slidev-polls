package site.asm0dey.slidev.polls.api.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;

/**
 * Cross-cutting coverage for the SPA catch-all rewiring (T087 / {@link SpaForwardingConfig}).
 *
 * <p>Scenarios:
 *
 * <ul>
 *   <li>{@code @TS-043} — {@code GET /{slug}} forwards to the voter SPA shell; the parallel {@code
 *       GET /api/polls/by-slug/{slug}} still returns JSON (a Problem envelope when the slug is
 *       unknown), proving the catch-all does not swallow the API.
 *   <li>{@code @TS-044} — {@code GET /admin/} serves the backoffice SPA shell while {@code GET
 *       /api/admin/polls} stays gated at 401 {@code AUTH_REQUIRED}.
 *   <li>{@code @BUG-002} — {@code GET /admin/} returns 200 with the real shell body (not a {@code
 *       StackOverflowError}) and {@code /admin/polls} deep-links resolve to the same shell via the
 *       SPA-fallback resource resolver.
 * </ul>
 *
 * <p>Test-scoped {@code src/test/resources/static/index.html} and {@code .../admin/index.html}
 * shells stand in for the built SPA assets that the Dockerfile copies in at package time —
 * production ships the real SPA bundles.
 *
 * <p>Note on assertion choice: the voter shell still goes through a {@code forward:} view so the
 * body is empty at the MockMvc boundary and {@link #forwardedUrl} is the authoritative check. The
 * backoffice shell is served directly by the static {@link
 * org.springframework.web.servlet.resource.PathResourceResolver} chain — MockMvc executes the
 * resource handler in-process, so we can (and must) assert on the rendered body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SpaCatchAllIT {

  @Autowired private MockMvc mvc;

  // @TS-043 — /{slug} is rewritten to the voter shell; unknown slugs still render the shell
  // (the voter SPA does the /api/polls/by-slug/{slug} lookup client-side and shows its own
  // "no such poll" copy). This keeps the SPA-shell response consistent whether the slug exists
  // or not — the server does not mis-hint to the crawler.
  @Test
  void root_forwards_to_voter_shell() throws Exception {
    mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
  }

  @Test
  void slug_path_forwards_to_voter_shell() throws Exception {
    mvc.perform(get("/some-talk"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/index.html"));
  }

  // @TS-043 — the API route with the SAME slug still returns JSON, proving the forward does not
  // swallow /api/**. No poll exists, so the response is a Problem envelope, but it is JSON and
  // not the voter shell.
  @Test
  void api_by_slug_is_not_swallowed_by_the_catch_all() throws Exception {
    mvc.perform(get("/api/polls/by-slug/some-talk"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // @TS-044 / @BUG-002 — /admin/ renders the backoffice shell directly from the static resource
  // chain. The PathResourceResolver serves the bytes in-process, so the response body carries the
  // shell's <base href="/admin/"> marker. The previous implementation used
  // forward:/admin/index.html
  // which re-dispatched through the same controller and blew up with StackOverflowError; see the
  // BUG-002 entry in bugs.md for the incident.
  @Test
  void admin_root_serves_backoffice_shell() throws Exception {
    mvc.perform(get("/admin/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-sp-shell=\"backoffice\"")));
  }

  // @TS-044 — /admin/polls is a client-side route inside the SPA, so the resolver must hand the
  // shell back for dot-less deep links. We assert the same shell marker as /admin/ to prove the
  // fallback path wired up, not a stale 404.
  @Test
  void admin_sub_route_serves_backoffice_shell() throws Exception {
    mvc.perform(get("/admin/polls"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-sp-shell=\"backoffice\"")));
  }

  // @BUG-002 — /admin/index.html must resolve to the literal file, not re-enter the SPA handler.
  // The explicit asset fetch proves the loop that caused StackOverflowError is gone.
  @Test
  void admin_index_html_serves_the_shell_file_without_looping() throws Exception {
    mvc.perform(get("/admin/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-sp-shell=\"backoffice\"")));
  }

  // @BUG-002 — a missing dotted resource under /admin/assets/** must return a real 404, not the
  // shell's HTML. Serving HTML for a JS URL would make the browser try to execute the shell as
  // JavaScript and would mask missing bundles behind a green 200.
  @Test
  void admin_missing_asset_returns_404_not_the_shell() throws Exception {
    mvc.perform(get("/admin/assets/does-not-exist.js")).andExpect(status().isNotFound());
  }

  // /admin without trailing slash needs to land at /admin/ — Vue Router's base is /admin/ and
  // asset URLs in the shell are relative to it, so a trailing-slashless landing would load
  // assets off / instead and break the app.
  @Test
  void admin_without_trailing_slash_redirects_to_slashed_form() throws Exception {
    mvc.perform(get("/admin"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/"));
  }

  // @BUG-002 — /admin/ must surface the shell's HTML content-type so browsers render it as a
  // document. Content-Type regressions here would stop the SPA from ever bootstrapping.
  @Test
  void admin_root_is_served_as_html() throws Exception {
    mvc.perform(get("/admin/"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", containsString("text/html")));
  }

  // @TS-044 — the backoffice SPA shell is served unauthenticated, but the data API that the
  // SPA calls stays gated: /api/admin/polls must still surface 401 AUTH_REQUIRED.
  @Test
  void admin_api_remains_gated_behind_auth() throws Exception {
    mvc.perform(get("/api/admin/polls"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }
}
