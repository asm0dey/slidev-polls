package site.asm0dey.slidev.polls.api.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.admin.dto.CreateUserRequest;
import site.asm0dey.slidev.polls.api.admin.dto.UserResponse;
import site.asm0dey.slidev.polls.core.service.AdminUserService;
import site.asm0dey.slidev.polls.core.service.CreateAdminCommand;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

  private final AdminUserService service;

  public AdminUserController(AdminUserService service) {
    this.service = service;
  }

  @GetMapping
  public List<UserResponse> list() {
    return service.listAdmins().stream()
        .map(u -> new UserResponse(u.username(), u.displayName(), u.createdAt()))
        .toList();
  }

  @PostMapping
  public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest body) {
    var created =
        service.createAdmin(
            new CreateAdminCommand(body.username(), body.password(), body.displayName()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new UserResponse(created.username(), created.displayName(), created.createdAt()));
  }
}
