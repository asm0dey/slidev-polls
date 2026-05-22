package site.asm0dey.slidev.polls.api.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_COLLABORATORS;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PollTransferIT {

  @Autowired DSLContext dsl;
  @Autowired PasswordEncoder encoder;
  @Autowired WebApplicationContext wac;
  @Autowired ObjectMapper mapper;
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(POLL_COLLABORATORS).execute();
    dsl.deleteFrom(POLLS).execute();
    dsl.deleteFrom(ADMIN_USER).execute();
    mvc =
        MockMvcBuilders.webAppContextSetup(wac)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "alice", "alice-password-12");
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "bob", "bob-password-1234");
  }

  private MockHttpSession login(String u, String p) throws Exception {
    var res =
        mvc.perform(
                post("/api/admin/login")
                    .contentType("application/json")
                    .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}")
                    .with(csrf()))
            .andExpect(status().isNoContent())
            .andReturn();
    return (MockHttpSession) res.getRequest().getSession(false);
  }

  private String createPoll(MockHttpSession s) throws Exception {
    String slug = "t-" + java.util.UUID.randomUUID();
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
  void transfer_movesOwnership_oldOwnerLosesAccess() throws Exception {
    MockHttpSession alice = login("alice", "alice-password-12");
    String pollId = createPoll(alice);
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/transfer")
                .session(alice)
                .with(csrf())
                .contentType("application/json")
                .content("{\"newOwnerUsername\":\"bob\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/polls/" + pollId).session(alice)).andExpect(status().isForbidden());
    MockHttpSession bob = login("bob", "bob-password-1234");
    mvc.perform(get("/api/admin/polls/" + pollId).session(bob))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isOwner").value(true));
  }

  @Test
  void nonOwnerTransfer_is403() throws Exception {
    MockHttpSession alice = login("alice", "alice-password-12");
    String pollId = createPoll(alice);
    MockHttpSession bob = login("bob", "bob-password-1234");
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/transfer")
                .session(bob)
                .with(csrf())
                .contentType("application/json")
                .content("{\"newOwnerUsername\":\"bob\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void transferToUnknownUser_is404() throws Exception {
    MockHttpSession alice = login("alice", "alice-password-12");
    String pollId = createPoll(alice);
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/transfer")
                .session(alice)
                .with(csrf())
                .contentType("application/json")
                .content("{\"newOwnerUsername\":\"ghost\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void selfTransfer_isNoOp200() throws Exception {
    MockHttpSession alice = login("alice", "alice-password-12");
    String pollId = createPoll(alice);
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/transfer")
                .session(alice)
                .with(csrf())
                .contentType("application/json")
                .content("{\"newOwnerUsername\":\"alice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isOwner").value(true));
  }

  @Test
  void transferToExistingCollaborator_removesCollaboratorRow() throws Exception {
    MockHttpSession alice = login("alice", "alice-password-12");
    String pollId = createPoll(alice);
    String collabUrl = "/api/admin/polls/" + pollId + "/collaborators";
    mvc.perform(
            post(collabUrl)
                .session(alice)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"bob\"}"))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/transfer")
                .session(alice)
                .with(csrf())
                .contentType("application/json")
                .content("{\"newOwnerUsername\":\"bob\"}"))
        .andExpect(status().isOk());
    int rows =
        dsl.fetchCount(
            POLL_COLLABORATORS,
            POLL_COLLABORATORS
                .POLL_ID
                .eq(java.util.UUID.fromString(pollId))
                .and(POLL_COLLABORATORS.USERNAME.eq("bob")));
    org.assertj.core.api.Assertions.assertThat(rows).isZero();
  }
}
