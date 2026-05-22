package site.asm0dey.slidev.polls.api.admin;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

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
import site.asm0dey.slidev.polls.persistence.jooq.Tables;

/**
 * Full-stack presenter authoring flow ported to {@code @QuarkusTest} + RestAssured. State-changing
 * admin calls use the real login flow (SP_SESSION + XSRF double-submit). POLLS is wiped before each
 * test so the listing assertions (length == 1) are stable across the shared Dev Services Postgres.
 */
@QuarkusTest
class PollAuthoringIT {

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
    dsl.deleteFrom(Tables.POLLS).execute();
    AdminUserTestFixtures.ensureAdmin(dsl, hasher, "alice", "correct-horse");
  }

  // @TS-002 — login, create a poll with two questions, confirm it lists with the right shape, fetch
  // its QR as a real PNG that decodes to the publicUrl.
  @Test
  void alice_creates_poll_with_two_questions_and_retrieves_qr() throws Exception {
    Session admin = loginAsAlice();
    String body =
        """
        {
          "title": "Quickstart demo",
          "questions": [
            { "prompt": "Which JVM?",     "options": [ { "label": "OpenJDK" }, { "label": "GraalVM" } ] },
            { "prompt": "Favourite IDE?", "options": [ { "label": "IntelliJ" }, { "label": "VS Code" } ] }
          ]
        }
        """;
    Response created =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .body("title", equalTo("Quickstart demo"))
            .body("slug", equalTo("quickstart-demo"))
            .body("questions.size()", equalTo(2))
            .body("publicUrl", matchesPattern("https?://.+/[a-z0-9-]{3,40}"))
            .extract()
            .response();

    String pollId = created.path("id");
    String publicUrl = created.path("publicUrl");

    admin
        .request()
        .when()
        .get("/api/admin/polls")
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].slug", equalTo("quickstart-demo"))
        .body("[0].publicUrl", matchesPattern("https?://.+/quickstart-demo"));

    byte[] qr =
        admin
            .request()
            .when()
            .get("/api/admin/polls/" + pollId + "/qr.png")
            .then()
            .statusCode(200)
            .header("Content-Type", "image/png")
            .extract()
            .asByteArray();
    assertThat(decodeQr(qr)).isEqualTo(publicUrl);
  }

  // @TS-A5-001 — allowedOrigins is accepted and echoed.
  @Test
  void createPollAcceptsAllowedOrigins() {
    Session admin = loginAsAlice();
    String body =
        """
        {
          "title": "T",
          "slug": "with-origins",
          "questions": [{"prompt":"p","options":[{"label":"A"},{"label":"B"}]}],
          "allowedOrigins": ["http://localhost:3030"]
        }
        """;
    String pollId =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .body("allowedOrigins[0]", equalTo("http://localhost:3030"))
            .extract()
            .path("id");

    admin.requestWithCsrf().when().delete("/api/admin/polls/" + pollId).then().statusCode(204);
  }

  // @TS-A5-002 — malformed origin → 400 ORIGIN_INVALID.
  @Test
  void rejectsMalformedOrigin() {
    Session admin = loginAsAlice();
    String body =
        """
        {
          "title": "T", "slug": "bad-origin",
          "questions": [{"prompt":"p","options":[{"label":"A"},{"label":"B"}]}],
          "allowedOrigins": ["not a url"]
        }
        """;
    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/api/admin/polls")
        .then()
        .statusCode(400)
        .body("code", equalTo("ORIGIN_INVALID"));
  }

  // @TS-006 — delete removes from list and later GETs return 404.
  @Test
  void alice_deletes_her_poll_and_subsequent_fetch_returns_404() {
    Session admin = loginAsAlice();
    String body =
        """
        {
          "title": "Will be deleted",
          "slug": "gone-soon",
          "questions": [
            { "prompt": "x?", "options": [ { "label": "a" }, { "label": "b" } ] }
          ]
        }
        """;
    String pollId =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    admin.requestWithCsrf().when().delete("/api/admin/polls/" + pollId).then().statusCode(204);

    admin
        .request()
        .when()
        .get("/api/admin/polls")
        .then()
        .statusCode(200)
        .body("findAll { it.id == '" + pollId + "' }.size()", equalTo(0));

    admin
        .request()
        .when()
        .get("/api/admin/polls/" + pollId)
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
  }

  @Test
  void patch_preservesQuestionAndOptionIds() {
    Session admin = loginAsAlice();
    String createBody =
        "{\"title\":\"keep ids\",\"slug\":\"keep-ids\","
            + "\"questions\":[{\"prompt\":\"Q?\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}";
    Response created =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body(createBody)
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .extract()
            .response();

    String pollId = created.path("id");
    String qId = created.path("questions[0].id");
    String oAId = created.path("questions[0].options[0].id");
    String oBId = created.path("questions[0].options[1].id");

    String patchBody =
        String.format(
            "{\"title\":\"keep ids\",\"questions\":[{\"id\":\"%s\",\"prompt\":\"Q"
                + " edited?\",\"options\":[{\"id\":\"%s\",\"label\":\"A"
                + " edited\"},{\"id\":\"%s\",\"label\":\"B\"}]}]}",
            qId, oAId, oBId);

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body(patchBody)
        .when()
        .patch("/api/admin/polls/" + pollId)
        .then()
        .statusCode(200)
        .body("questions[0].id", equalTo(qId))
        .body("questions[0].prompt", equalTo("Q edited?"))
        .body("questions[0].options[0].id", equalTo(oAId))
        .body("questions[0].options[0].label", equalTo("A edited"));
  }

  @Test
  void clone_returnsNewPollWithFreshIds() {
    Session admin = loginAsAlice();
    String body =
        "{\"title\":\"talk\",\"slug\":\"talk-original\",\"questions\":[{\"prompt\":\"Q?\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}";
    Response src =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .extract()
            .response();
    String srcId = src.path("id");
    String srcQId = src.path("questions[0].id");

    admin
        .requestWithCsrf()
        .when()
        .post("/api/admin/polls/" + srcId + "/clone")
        .then()
        .statusCode(201)
        .body("id", not(equalTo(srcId)))
        .body("title", equalTo("Copy of talk"))
        .body("questions[0].id", not(equalTo(srcQId)));
  }

  @Test
  void clearVotes_returnsPollWithoutVotesAndQuestionIdsPreserved() {
    Session admin = loginAsAlice();
    String body =
        "{\"title\":\"t\",\"slug\":\"clear-it\",\"questions\":[{\"prompt\":\"Q?\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}";
    Response created =
        admin
            .requestWithCsrf()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post("/api/admin/polls")
            .then()
            .statusCode(201)
            .extract()
            .response();
    String pollId = created.path("id");
    String qId = created.path("questions[0].id");

    admin
        .requestWithCsrf()
        .contentType(ContentType.JSON)
        .body("{\"questionId\":\"" + qId + "\"}")
        .when()
        .post("/api/admin/polls/" + pollId + "/open")
        .then()
        .statusCode(200);

    // The literal colon in "votes:clear" must reach the server unencoded (the production frontend
    // sends it raw); RestAssured would otherwise percent-encode it to %3A and miss the route.
    admin
        .requestWithCsrf()
        .urlEncodingEnabled(false)
        .when()
        .post("/api/admin/polls/" + pollId + "/votes:clear")
        .then()
        .statusCode(200)
        .body("activeQuestionId", org.hamcrest.Matchers.nullValue())
        .body("status", equalTo("DRAFT"))
        .body("questions[0].id", equalTo(qId))
        .body("questions[0].status", equalTo("DRAFT"));
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
