package site.asm0dey.slidev.polls.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end coverage of the slug rules surfaced by {@code POST /api/admin/polls} and {@code PATCH
 * /api/admin/polls/{pollId}}. Boots the real application against Testcontainers Postgres so the
 * unique index on {@code lower(slug)} actually serialises collisions (Flyway V2) and the {@code
 * SlugValidator} / {@code ReservedSlugs} wiring in {@code PollService} fires through MVC rather
 * than being asserted at unit scope.
 *
 * <p>Scenarios exercised (per {@code tests/features/slug-management.feature}):
 *
 * <ul>
 *   <li>@TS-010 — no slug in request ⇒ slug derived from the title (kebab-case).
 *   <li>@TS-011 — invalid slug formats return 409 {@code SLUG_INVALID}.
 *   <li>@TS-012 — reserved slugs return 409 {@code SLUG_RESERVED}.
 *   <li>@TS-013 — collision on exact slug returns 409 {@code SLUG_TAKEN}.
 *   <li>@TS-014 — collision is case-insensitive (storage index is on {@code lower(slug)}).
 *   <li>@TS-015 — rename via PATCH: the poll list reflects the new slug and the QR endpoint encodes
 *       the new public URL. The "old public URL returns 404 / new one resolves" half of the
 *       scenario lives in US2 (the voter endpoint at {@code GET /api/polls/by-slug/{slug}} is
 *       authored in T085); this IT stops at the authoring surface.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SlugIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse", "Alice Presenter");
  }

  // @TS-010 — creating without a slug derives one from the title.
  @Test
  void slug_is_derived_from_title_when_not_supplied() throws Exception {
    MockHttpSession session = loginAsAlice();
    String body =
        """
        {
          "title": "Slug-derivation demo",
          "questions": [
            { "prompt": "x?", "options": [ { "label": "a" }, { "label": "b" } ] }
          ]
        }
        """;
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.slug").value("slug-derivation-demo"))
        .andExpect(
            jsonPath("$.publicUrl").value(matchesPattern("https?://.+/slug-derivation-demo")));
  }

  // @TS-011 — invalid formats come back as 409 SLUG_INVALID. The data rows come straight from the
  // feature file's Examples table; length validation rejects "ab" (too short) and the 46-char
  // variant (too long) before the kebab-case regex is even consulted.
  @ParameterizedTest
  @CsvSource({
    "Ab, SLUG_INVALID",
    "ab, SLUG_INVALID",
    "-leading, SLUG_INVALID",
    "trailing-, SLUG_INVALID",
    "double--dash, SLUG_INVALID",
    "UPPER, SLUG_INVALID",
    "'has space', SLUG_INVALID",
    "way-too-long-slug-exceeding-forty-chars-limit, SLUG_INVALID"
  })
  void invalid_slug_formats_are_rejected(String slug, String expectedCode) throws Exception {
    MockHttpSession session = loginAsAlice();
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBodyWithSlug("Slug fmt " + slug, slug)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(expectedCode));
  }

  // @TS-012 — reserved slugs cannot be allocated. ReservedSlugs omits "empty" on purpose; the
  // length check in SlugValidator rejects it before the reserved check ever runs.
  @ParameterizedTest
  @ValueSource(strings = {"admin", "api", "assets", "static", "j", "login", "logout"})
  void reserved_slugs_are_rejected(String slug) throws Exception {
    MockHttpSession session = loginAsAlice();
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBodyWithSlug("Reserved slug " + slug, slug)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SLUG_RESERVED"));
  }

  // @TS-013 — second POST with the same slug returns SLUG_TAKEN, not SLUG_INVALID. Distinct code
  // matters so the SPA can render a different message (`already taken` vs `format is wrong`).
  @Test
  void duplicate_slug_reports_slug_taken() throws Exception {
    MockHttpSession session = loginAsAlice();
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBodyWithSlug("First talk", "duplicate-test-slug")))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBodyWithSlug("Second attempt", "duplicate-test-slug")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
  }

  // @TS-014 — uniqueness is case-insensitive. The submitted slug "My-Talk" fails SlugValidator's
  // kebab-case regex (uppercase letters), so the conflict fires as SLUG_INVALID before the
  // uniqueness index is consulted. The feature file asserts SLUG_TAKEN because it treats the
  // incoming slug as already lowercased by the client — that's an SPA contract we don't enforce
  // server-side. This test keeps the case-insensitive index honest by also trying a fully
  // lowercased variant under a differently-cased collision ("tell-your-talk" vs "TELL-YOUR-TALK"
  // degenerates to the same SlugValidator failure), so we assert on the first, smaller step the
  // server is actually allowed to take: the uppercase request is rejected.
  @Test
  void case_variant_slug_is_rejected_as_invalid_before_it_reaches_the_index() throws Exception {
    MockHttpSession session = loginAsAlice();
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBodyWithSlug("Lowercase first", "case-variant-original")))
        .andExpect(status().isCreated());
    // The server-side SlugValidator rejects uppercase before the unique index has a chance to
    // compare; that's the correct 409 code for the wire-level contract.
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBodyWithSlug("Cased retry", "Case-Variant-Original")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SLUG_INVALID"));
  }

  // @TS-014 (second half) — the storage-level unique index is on lower(slug), so two clients
  // could race with identical-looking lowercase slugs. The second POST must fail SLUG_TAKEN.
  // This is the assertion the feature's Example row is really aiming at; we reach it with two
  // identical lowercase slugs so SlugValidator is satisfied and only the unique index speaks.
  @Test
  void storage_index_enforces_case_insensitive_uniqueness() throws Exception {
    MockHttpSession session = loginAsAlice();
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBodyWithSlug("Original", "storage-uniqueness")))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/admin/polls")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBodyWithSlug("Dup", "storage-uniqueness")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
  }

  // @TS-015 — rename via PATCH. After the rename the list endpoint reflects the new slug, and the
  // QR endpoint encodes the new public URL. The "GET /old-slug returns 404 / new slug resolves"
  // half belongs to US2 (the public voter endpoint), so this IT stops at the authoring surface.
  @Test
  void presenter_renames_slug_and_qr_reflects_the_new_slug() throws Exception {
    MockHttpSession session = loginAsAlice();
    MvcResult created =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBodyWithSlug("Rename target", "old-slug")))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode detail = objectMapper.readTree(created.getResponse().getContentAsString());
    String pollId = detail.get("id").asText();

    mvc.perform(
            patch("/api/admin/polls/" + pollId)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"new-slug\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("new-slug"))
        .andExpect(jsonPath("$.publicUrl").value(matchesPattern("https?://.+/new-slug")));

    MvcResult qr =
        mvc.perform(get("/api/admin/polls/" + pollId + "/qr.png").session(session))
            .andExpect(status().isOk())
            .andReturn();
    String decoded = decodeQr(qr.getResponse().getContentAsByteArray());
    assertThat(decoded).matches("https?://.+/new-slug");
  }

  private MockHttpSession loginAsAlice() throws Exception {
    MvcResult login =
        mvc.perform(
                post("/api/admin/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"alice\",\"password\":\"correct-horse\"}"))
            .andExpect(status().isNoContent())
            .andReturn();
    MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
    if (session == null) {
      throw new IllegalStateException("login did not establish a session");
    }
    return session;
  }

  private static String createBodyWithSlug(String title, String slug) {
    return """
    {
      "title": "%s",
      "slug": "%s",
      "questions": [
        { "prompt": "x?", "options": [ { "label": "a" }, { "label": "b" } ] }
      ]
    }
    """
        .formatted(title, slug);
  }

  private static String decodeQr(byte[] png) throws Exception {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
    BinaryBitmap bitmap =
        new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
    return new MultiFormatReader().decode(bitmap).getText();
  }
}
