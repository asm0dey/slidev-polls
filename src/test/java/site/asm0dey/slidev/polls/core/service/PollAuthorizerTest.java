package site.asm0dey.slidev.polls.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.error.NotOwnerException;

@ExtendWith(MockitoExtension.class)
class PollAuthorizerTest {

  @Mock PollCollaboratorRepository collaborators;

  private Poll poll(String owner) {
    UUID id = UUID.randomUUID();
    return new Poll(
        id,
        owner,
        "T",
        "t",
        PollStatus.DRAFT,
        null,
        List.of(),
        List.of(),
        Instant.now(),
        Instant.now());
  }

  @Test
  void ownerIsOwnerAndEditor() {
    PollAuthorizer auth = new PollAuthorizer(collaborators);
    Poll p = poll("alice");
    assertThat(auth.isOwner(p, "alice")).isTrue();
    assertThat(auth.isEditor(p, "alice")).isTrue();
  }

  @Test
  void collaboratorIsEditorButNotOwner() {
    PollAuthorizer auth = new PollAuthorizer(collaborators);
    Poll p = poll("alice");
    when(collaborators.exists(p.id(), "bob")).thenReturn(true);
    assertThat(auth.isOwner(p, "bob")).isFalse();
    assertThat(auth.isEditor(p, "bob")).isTrue();
  }

  @Test
  void strangerIsNeither() {
    PollAuthorizer auth = new PollAuthorizer(collaborators);
    Poll p = poll("alice");
    when(collaborators.exists(p.id(), "eve")).thenReturn(false);
    assertThat(auth.isEditor(p, "eve")).isFalse();
    assertThatThrownBy(() -> auth.requireEditor(p, "eve")).isInstanceOf(NotOwnerException.class);
    assertThatThrownBy(() -> auth.requireOwner(p, "eve")).isInstanceOf(NotOwnerException.class);
  }
}
