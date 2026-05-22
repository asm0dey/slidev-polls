package site.asm0dey.slidev.polls.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.admin.dto.AccountResponse;
import site.asm0dey.slidev.polls.api.admin.dto.ChangePasswordRequest;
import site.asm0dey.slidev.polls.api.security.session.SessionRevoker;
import site.asm0dey.slidev.polls.core.service.AdminUserService;

/**
 * Current-account introspection for the backoffice SPA. {@code isAdmin} drives admin-only UI
 * affordances (reset/block/unblock); it is advisory only — every privileged endpoint re-checks
 * bootstrap-admin status server-side.
 */
@RestController
@RequestMapping("/api/admin/account")
public class AdminAccountController {

  private final AdminUserService service;
  private final SessionRevoker sessionRevoker;

  public AdminAccountController(AdminUserService service, SessionRevoker sessionRevoker) {
    this.service = service;
    this.sessionRevoker = sessionRevoker;
  }

  @GetMapping
  public AccountResponse account(@AuthenticationPrincipal UserDetails principal) {
    String username = principal.getUsername();
    return new AccountResponse(username, service.isBootstrapAdmin(username));
  }

  @PostMapping("/password")
  public ResponseEntity<Void> changePassword(
      @Valid @RequestBody ChangePasswordRequest body,
      @AuthenticationPrincipal UserDetails principal,
      HttpServletRequest request) {
    String username = principal.getUsername();
    service.changeOwnPassword(username, body.currentPassword(), body.newPassword());
    // Keep the session that just authenticated here; kill the user's other sessions.
    sessionRevoker.expireAllExcept(username, request.getSession().getId());
    return ResponseEntity.noContent().build();
  }
}
