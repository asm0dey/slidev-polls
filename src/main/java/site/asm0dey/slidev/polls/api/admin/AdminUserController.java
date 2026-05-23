package site.asm0dey.slidev.polls.api.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.admin.dto.CreateUserRequest;
import site.asm0dey.slidev.polls.api.admin.dto.ResetPasswordRequest;
import site.asm0dey.slidev.polls.api.admin.dto.UserResponse;
import site.asm0dey.slidev.polls.api.security.session.SessionRevoker;
import site.asm0dey.slidev.polls.core.error.AdminRequiredException;
import site.asm0dey.slidev.polls.core.service.AdminUserService;
import site.asm0dey.slidev.polls.core.service.CreateAdminCommand;
import site.asm0dey.slidev.polls.core.service.Usernames;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

  private final AdminUserService service;
  private final SessionRevoker sessionRevoker;

  public AdminUserController(AdminUserService service, SessionRevoker sessionRevoker) {
    this.service = service;
    this.sessionRevoker = sessionRevoker;
  }

  @GetMapping
  public List<UserResponse> list() {
    java.util.Set<String> blocked = service.listBlockedUsernames();
    return service.listAdmins().stream()
        .map(u -> new UserResponse(u.username(), u.createdAt(), blocked.contains(u.username())))
        .toList();
  }

  @PostMapping
  public ResponseEntity<UserResponse> create(
      @Valid @RequestBody CreateUserRequest body, @AuthenticationPrincipal UserDetails principal) {
    requireAdmin(principal);
    var created = service.createAdmin(new CreateAdminCommand(body.username(), body.password()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new UserResponse(created.username(), created.createdAt(), false));
  }

  @PostMapping("/{username}/block")
  public ResponseEntity<Void> block(
      @PathVariable String username, @AuthenticationPrincipal UserDetails principal) {
    requireAdmin(principal);
    service.block(principal.getUsername(), username);
    sessionRevoker.expireAll(Usernames.normalize(username));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{username}/unblock")
  public ResponseEntity<Void> unblock(
      @PathVariable String username, @AuthenticationPrincipal UserDetails principal) {
    requireAdmin(principal);
    service.unblock(username);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{username}/password-reset")
  public ResponseEntity<Void> resetPassword(
      @PathVariable String username,
      @Valid @RequestBody ResetPasswordRequest body,
      @AuthenticationPrincipal UserDetails principal) {
    requireAdmin(principal);
    String target = Usernames.normalize(username);
    service.resetPassword(target, body.newPassword());
    sessionRevoker.expireAll(target);
    return ResponseEntity.noContent().build();
  }

  private void requireAdmin(UserDetails principal) {
    if (!service.isBootstrapAdmin(principal.getUsername())) {
      throw new AdminRequiredException();
    }
  }
}
