package site.asm0dey.slidev.polls.api.admin;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

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
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

/**
 * Focused coverage of the admin QR endpoint (@TS-026), ported to {@code @QuarkusTest} +
 * RestAssured. The endpoint returns 200 with {@code Content-Type: image/png}, a decodable PNG whose
 * payload is the absolute public URL ending in the poll's slug.
 */
@QuarkusTest
class QrEndpointIT {

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

  // @TS-026 — the QR PNG decodes to a URL ending in the poll's slug.
  @Test
  void qr_png_decodes_to_the_polls_public_url() throws Exception {
    Session admin = loginAsAlice();
    String pollId =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "title": "QR decode target",
                  "slug": "qr-decode-target",
                  "questions": [
                    { "prompt": "x?", "options": [ { "label": "a" }, { "label": "b" } ] }
                  ]
                }
                """)
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    byte[] bytes =
        admin
            .request()
            .when()
            .get("/api/admin/polls/" + pollId + "/qr.png")
            .then()
            .statusCode(200)
            .header("Content-Type", "image/png")
            .extract()
            .asByteArray();

    assertThat(bytes).isNotEmpty();
    assertThat(decodeQr(bytes)).matches("https?://.+/qr-decode-target");
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
