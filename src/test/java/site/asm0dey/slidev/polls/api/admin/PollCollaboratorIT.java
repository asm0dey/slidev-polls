package site.asm0dey.slidev.polls.api.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class PollCollaboratorIT {

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
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "owner", "owner-password-12");
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "colla", "colla-password-12");
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "eve", "eve-password-1234");
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
  void ownerAddsCollaborator_thenCollaboratorCanEditButNotDeleteOrShare() throws Exception {
    MockHttpSession owner = login("owner", "owner-password-12");
    String pollId = createPoll(owner);

    mvc.perform(
            post("/api/admin/polls/" + pollId + "/collaborators")
                .session(owner)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"colla\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value("colla"));

    MockHttpSession colla = login("colla", "colla-password-12");
    mvc.perform(get("/api/admin/polls/" + pollId).session(colla))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isOwner").value(false));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/admin/polls/" + pollId)
                .session(colla)
                .with(csrf())
                .contentType("application/json")
                .content("{\"title\":\"Edited by colla\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/polls").session(colla))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[?(@.id=='" + pollId + "')].isOwner")
                .value(org.hamcrest.Matchers.contains(false)));
    mvc.perform(delete("/api/admin/polls/" + pollId).session(colla).with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/collaborators")
                .session(colla)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"eve\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void addUnknownUser_is404() throws Exception {
    MockHttpSession owner = login("owner", "owner-password-12");
    String pollId = createPoll(owner);
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/collaborators")
                .session(owner)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"ghost\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void addOwnerAsCollaborator_is409() throws Exception {
    MockHttpSession owner = login("owner", "owner-password-12");
    String pollId = createPoll(owner);
    mvc.perform(
            post("/api/admin/polls/" + pollId + "/collaborators")
                .session(owner)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"owner\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CANNOT_SHARE_WITH_OWNER"));
  }

  @Test
  void addDuplicate_is409() throws Exception {
    MockHttpSession owner = login("owner", "owner-password-12");
    String pollId = createPoll(owner);
    String url = "/api/admin/polls/" + pollId + "/collaborators";
    mvc.perform(
            post(url)
                .session(owner)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"colla\"}"))
        .andExpect(status().isCreated());
    mvc.perform(
            post(url)
                .session(owner)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"colla\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COLLABORATOR_EXISTS"));
  }

  @Test
  void removeCollaborator_revokesAccess_andIsIdempotent() throws Exception {
    MockHttpSession owner = login("owner", "owner-password-12");
    String pollId = createPoll(owner);
    String url = "/api/admin/polls/" + pollId + "/collaborators";
    mvc.perform(
            post(url)
                .session(owner)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"colla\"}"))
        .andExpect(status().isCreated());
    mvc.perform(delete(url + "/colla").session(owner).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(delete(url + "/colla").session(owner).with(csrf()))
        .andExpect(status().isNoContent());
    MockHttpSession colla = login("colla", "colla-password-12");
    mvc.perform(get("/api/admin/polls/" + pollId).session(colla)).andExpect(status().isForbidden());
  }
}
