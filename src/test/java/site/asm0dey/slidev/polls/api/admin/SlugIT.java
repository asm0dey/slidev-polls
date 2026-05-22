package site.asm0dey.slidev.polls.api.admin;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

/**
 * End-to-end slug rules surfaced by {@code POST /api/admin/polls} and {@code PATCH
 * /api/admin/polls/{id}}, ported to {@code @QuarkusTest} + RestAssured against Dev Services
 * Postgres so the {@code lower(slug)} unique index actually serialises collisions.
 *
 * <ul>
 *   <li>@TS-010 — no slug ⇒ derived from the title (kebab-case).
 *   <li>@TS-011 — invalid slug formats → 409 SLUG_INVALID.
 *   <li>@TS-012 — reserved slugs → 409 SLUG_RESERVED.
 *   <li>@TS-013 — collision → 409 SLUG_TAKEN.
 *   <li>@TS-014 — case-insensitive collision behaviour.
 *   <li>@TS-015 — rename via PATCH reflected in QR.
 * </ul>
 */
@QuarkusTest
class SlugIT {

  @Inject DSLContext dsl;
  @Inject Argon2PasswordHasher hasher;

  @BeforeAll
  static void noCharsetOnJson() {
    RestAssured.config =
        RestAssured.config()
            .encoderConfig(
                EncoderConfig.encoderConfig()
                    .appendDefaultContentCharsetToContentTypeIfUndefined(false));
  }

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, hasher, "alice", "correct-horse");
  }

  // @TS-010 — creating without a slug derives one from the title.
  @Test
  void slug_is_derived_from_title_when_not_supplied() {
    Session admin = loginAsAlice();
    String body =
        """
        {
          "title": "Slug-derivation demo",
          "questions": [
            { "prompt": "x?", "options": [ { "label": "a" }, { "label": "b" } ] }
          ]
        }
        """;
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(201)
        .body("slug", equalTo("slug-derivation-demo"))
        .body("publicUrl", matchesPattern("https?://.+/slug-derivation-demo"));
  }

  // @TS-011 — invalid formats → 409 SLUG_INVALID.
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
  void invalid_slug_formats_are_rejected(String slug, String expectedCode) {
    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(createBodyWithSlug("Slug fmt " + slug, slug))
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(409)
        .body("code", equalTo(expectedCode));
  }

  // @TS-012 — reserved slugs → 409 SLUG_RESERVED.
  @ParameterizedTest
  @ValueSource(strings = {"admin", "api", "assets", "static", "j", "login", "logout"})
  void reserved_slugs_are_rejected(String slug) {
    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(createBodyWithSlug("Reserved slug " + slug, slug))
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(409)
        .body("code", equalTo("SLUG_RESERVED"));
  }

  // @TS-013 — second POST with the same slug → SLUG_TAKEN.
  @Test
  void duplicate_slug_reports_slug_taken() {
    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(createBodyWithSlug("First talk", "duplicate-test-slug"))
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(201);
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(createBodyWithSlug("Second attempt", "duplicate-test-slug"))
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(409)
        .body("code", equalTo("SLUG_TAKEN"));
  }

  // @TS-014 — uppercase request rejected as SLUG_INVALID before reaching the index.
  @Test
  void case_variant_slug_is_rejected_as_invalid_before_it_reaches_the_index() {
    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(createBodyWithSlug("Lowercase first", "case-variant-original"))
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(201);
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(createBodyWithSlug("Cased retry", "Case-Variant-Original"))
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(409)
        .body("code", equalTo("SLUG_INVALID"));
  }

  // @TS-014 (second half) — storage-level unique index enforces case-insensitive uniqueness.
  @Test
  void storage_index_enforces_case_insensitive_uniqueness() {
    Session admin = loginAsAlice();
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(createBodyWithSlug("Original", "storage-uniqueness"))
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(201);
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(createBodyWithSlug("Dup", "storage-uniqueness"))
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(409)
        .body("code", equalTo("SLUG_TAKEN"));
  }

  // @TS-015 — rename via PATCH; QR reflects the new public URL.
  @Test
  void presenter_renames_slug_and_qr_reflects_the_new_slug() throws Exception {
    Session admin = loginAsAlice();
    String pollId =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body(createBodyWithSlug("Rename target", "old-slug"))
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{\"slug\":\"new-slug\"}")
        .when()
        .patch("/api/admin/polls/" + pollId)
        .then()
        .statusCode(200)
        .body("slug", equalTo("new-slug"))
        .body("publicUrl", matchesPattern("https?://.+/new-slug"));

    byte[] qr =
        admin
            .request()
            .when()
            .get("/api/admin/polls/" + pollId + "/qr.png")
            .then()
            .statusCode(200)
            .extract()
            .asByteArray();
    assertThat(decodeQr(qr)).matches("https?://.+/new-slug");
  }

  private Session loginAsAlice() {
    Response login =
        given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"alice\",\"password\":\"correct-horse\"}")
            .when()
            .post("/api/admin/login")
            .then()
            .statusCode(204)
            .extract()
            .response();
    String session = login.getCookie("SP_SESSION");
    String xsrf = login.getCookie("XSRF-TOKEN");
    assertThat(session).isNotBlank();
    assertThat(xsrf).isNotBlank();
    return new Session(session, xsrf);
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

  private record Session(String sessionCookie, String xsrfCookie) {
    RequestSpecification request() {
      return given().cookie("SP_SESSION", sessionCookie);
    }

    RequestSpecification requestWithCsrf() {
      return given()
          .cookie("SP_SESSION", sessionCookie)
          .cookie("XSRF-TOKEN", xsrfCookie)
          .header("X-XSRF-TOKEN", xsrfCookie);
    }
  }
}
