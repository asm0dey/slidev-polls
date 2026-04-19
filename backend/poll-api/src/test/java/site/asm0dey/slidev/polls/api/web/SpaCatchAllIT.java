package site.asm0dey.slidev.polls.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 *   <li>{@code @TS-044} — {@code GET /admin/} forwards to the backoffice SPA shell while {@code GET
 *       /api/admin/polls} stays gated at 401 {@code AUTH_REQUIRED}.
 * </ul>
 *
 * <p>Test-scoped {@code src/test/resources/static/index.html} and {@code .../admin/index.html}
 * shells stand in for the built SPA assets that {@code scripts/build-frontends.sh} copies in at
 * package time — production ships the real SPA bundles.
 *
 * <p>Note on assertion choice: MockMvc records the forwarded URL on the request attribute but does
 * not chain-execute the forward into the static resource handler, so the response body on a {@code
 * forward:/index.html} view is empty at the MockMvc boundary. The {@link #forwardedUrl} matcher is
 * the authoritative check — proving the forward targets the right shell file — and is the pattern
 * the admin-routing slice tests use for the same reason.
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

  // @TS-044 — /admin/ renders the backoffice shell. /admin/polls is a client-side route inside
  // the SPA, so the server must hand the shell back there too.
  @Test
  void admin_root_forwards_to_backoffice_shell() throws Exception {
    mvc.perform(get("/admin/"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/admin/index.html"));
  }

  @Test
  void admin_sub_route_forwards_to_backoffice_shell() throws Exception {
    mvc.perform(get("/admin/polls"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/admin/index.html"));
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
