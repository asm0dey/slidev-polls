package site.asm0dey.slidev.polls.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Focused end-to-end coverage of the admin QR endpoint (@TS-026). {@code PollAuthoringIT} and
 * {@code SlugIT} already decode the QR incidentally as part of their own narratives; this IT
 * isolates the check so TS-026's assertions live in one named place and any future QR-format
 * regression flags here rather than hiding inside an unrelated test.
 *
 * <p>Assertions: the endpoint returns 200 with {@code Content-Type: image/png}, the body is a
 * decodable PNG, and the decoded payload is the absolute public URL whose path ends in the poll's
 * slug. US2 will reuse this check from the voter side via the same slug contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class QrEndpointIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse");
  }

  // @TS-026 — the QR PNG decodes to a URL ending in the poll's slug.
  @Test
  void qr_png_decodes_to_the_polls_public_url() throws Exception {
    MockHttpSession session = loginAsAlice();
    MvcResult created =
        mvc.perform(
                post("/api/admin/polls")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "title": "QR decode target",
                          "slug": "qr-decode-target",
                          "questions": [
                            { "prompt": "x?", "options": [ { "label": "a" }, { "label": "b" } ] }
                          ]
                        }
                        """))
            .andExpect(status().isCreated())
            .andReturn();
    String pollId =
        objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString();

    MvcResult qr =
        mvc.perform(get("/api/admin/polls/" + pollId + "/qr.png").session(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
            .andReturn();

    byte[] bytes = qr.getResponse().getContentAsByteArray();
    assertThat(bytes).isNotEmpty();
    String decoded = decodeQr(bytes);
    assertThat(decoded).matches("https?://.+/qr-decode-target");
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

  private static String decodeQr(byte[] png) throws Exception {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
    BinaryBitmap bitmap =
        new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
    return new MultiFormatReader().decode(bitmap).getText();
  }
}
