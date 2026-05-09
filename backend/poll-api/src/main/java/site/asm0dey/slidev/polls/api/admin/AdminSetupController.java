package site.asm0dey.slidev.polls.api.admin;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.admin.dto.SetupRequest;
import site.asm0dey.slidev.polls.api.admin.dto.SetupStatusResponse;
import site.asm0dey.slidev.polls.api.admin.dto.UserResponse;
import site.asm0dey.slidev.polls.core.service.AdminUserService;
import site.asm0dey.slidev.polls.core.service.CreateAdminCommand;

/**
 * First-run bootstrap. Both endpoints are public on the filter chain — the gating happens in the
 * service: createInitialAdmin throws SetupLockedException once admin_user is non-empty, which the
 * GlobalExceptionHandler maps to 409 Problem(SETUP_LOCKED).
 */
@RestController
@RequestMapping("/api/admin/setup")
public class AdminSetupController {

  private final AdminUserService service;

  public AdminSetupController(AdminUserService service) {
    this.service = service;
  }

  @GetMapping("/status")
  public SetupStatusResponse status() {
    return new SetupStatusResponse(service.isSetupRequired());
  }

  @PostMapping
  public ResponseEntity<UserResponse> setup(@Valid @RequestBody SetupRequest body) {
    var created =
        service.createInitialAdmin(
            new CreateAdminCommand(body.username(), body.password(), body.displayName()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new UserResponse(created.username(), created.displayName(), created.createdAt()));
  }
}
