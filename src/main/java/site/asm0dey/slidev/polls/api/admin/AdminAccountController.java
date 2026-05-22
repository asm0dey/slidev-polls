package site.asm0dey.slidev.polls.api.admin;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.admin.dto.AccountResponse;
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

  public AdminAccountController(AdminUserService service) {
    this.service = service;
  }

  @GetMapping
  public AccountResponse account(@AuthenticationPrincipal UserDetails principal) {
    String username = principal.getUsername();
    return new AccountResponse(username, service.isBootstrapAdmin(username));
  }
}
