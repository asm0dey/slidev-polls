package site.asm0dey.slidev.polls.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.DECK_TOKENS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminBlockIT {

  @Autowired MockMvc mvc;
  @Autowired DSLContext dsl;
  @Autowired PasswordEncoder encoder;
  @Autowired ObjectMapper mapper;
  @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(DECK_TOKENS).execute();
    dsl.deleteFrom(POLLS).execute();
    dsl.deleteFrom(ADMIN_USER).execute();
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "alice", "alice-password-12"); // bootstrap admin
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "bob", "bob-password-1234"); // target
  }

  private MockHttpSession login(String u, String p) throws Exception {
    MockHttpSession session = new MockHttpSession(null, UUID.randomUUID().toString());
    mvc.perform(
            post("/api/admin/login")
                .contentType("application/json")
                .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}")
                .with(csrf())
                .session(session))
        .andExpect(status().isNoContent());
    return session;
  }

  private String createPoll(MockHttpSession s) throws Exception {
    String slug = "t-" + UUID.randomUUID();
    var res =
        mvc.perform(
                post("/api/admin/polls")
                    .session(s)
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        "{\"title\":\"T\",\"slug\":\""
                            + slug
                            + "\",\"questions\":[{\"prompt\":\"Q\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}"))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
    return body.get("id").asText();
  }

  @Test
  void block_rejectsLogin_expiresSessions_revokesTokens_keepsOwnership() throws Exception {
    MockHttpSession bob = login("bob", "bob-password-1234");
    String pollId = createPoll(bob);
    UUID tokenId = UUID.randomUUID();
    dsl.insertInto(DECK_TOKENS)
        .set(DECK_TOKENS.ID, tokenId)
        .set(DECK_TOKENS.POLL_ID, UUID.fromString(pollId))
        .set(DECK_TOKENS.TOKEN_HASH, "hash-" + tokenId)
        .set(DECK_TOKENS.MINTED_BY, "bob")
        .set(DECK_TOKENS.CREATED_AT, OffsetDateTime.now())
        .execute();
    assertThat(sessions.findByPrincipalName("bob")).isNotEmpty();

    MockHttpSession admin = login("alice", "alice-password-12");
    mvc.perform(post("/api/admin/users/bob/block").session(admin).with(csrf()))
        .andExpect(status().isNoContent());

    mvc.perform(
            post("/api/admin/login")
                .contentType("application/json")
                .content("{\"username\":\"bob\",\"password\":\"bob-password-1234\"}")
                .with(csrf()))
        .andExpect(status().isUnauthorized());
    assertThat(sessions.findByPrincipalName("bob")).isEmpty();
    assertThat(
            dsl.select(DECK_TOKENS.REVOKED_AT)
                .from(DECK_TOKENS)
                .where(DECK_TOKENS.ID.eq(tokenId))
                .fetchOne(DECK_TOKENS.REVOKED_AT))
        .isNotNull();
    assertThat(
            dsl.select(POLLS.OWNER_USERNAME)
                .from(POLLS)
                .where(POLLS.ID.eq(UUID.fromString(pollId)))
                .fetchOne(POLLS.OWNER_USERNAME))
        .isEqualTo("bob");
  }

  @Test
  void unblock_restoresLogin() throws Exception {
    MockHttpSession admin = login("alice", "alice-password-12");
    mvc.perform(post("/api/admin/users/bob/block").session(admin).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(post("/api/admin/users/bob/unblock").session(admin).with(csrf()))
        .andExpect(status().isNoContent());
    login("bob", "bob-password-1234");
  }

  @Test
  void blockSelf_is409() throws Exception {
    MockHttpSession admin = login("alice", "alice-password-12");
    mvc.perform(post("/api/admin/users/alice/block").session(admin).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_BLOCKED"));
  }

  @Test
  void blockBootstrapAdmin_byAnotherAdmin_is409() throws Exception {
    MockHttpSession admin = login("alice", "alice-password-12");
    mvc.perform(post("/api/admin/users/alice/block").session(admin).with(csrf()))
        .andExpect(status().isConflict());
  }

  @Test
  void nonAdminBlock_is403() throws Exception {
    MockHttpSession bob = login("bob", "bob-password-1234");
    mvc.perform(post("/api/admin/users/alice/block").session(bob).with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
  }

  @Test
  void userList_reportsBlocked() throws Exception {
    MockHttpSession admin = login("alice", "alice-password-12");
    mvc.perform(post("/api/admin/users/bob/block").session(admin).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/admin/users").session(admin))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[?(@.username=='bob')].blocked")
                .value(org.hamcrest.Matchers.contains(true)));
  }
}
