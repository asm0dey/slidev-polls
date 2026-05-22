package site.asm0dey.slidev.polls.api.admin;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.List;
import org.jboss.resteasy.reactive.RestResponse;
import site.asm0dey.slidev.polls.api.admin.dto.CreateUserRequest;
import site.asm0dey.slidev.polls.api.admin.dto.UserResponse;
import site.asm0dey.slidev.polls.core.service.AdminUserService;
import site.asm0dey.slidev.polls.core.service.CreateAdminCommand;

@Path("/api/admin/users")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed("ADMIN")
public class AdminUserController {

  private final AdminUserService service;

  public AdminUserController(AdminUserService service) {
    this.service = service;
  }

  @GET
  public List<UserResponse> list() {
    return service.listAdmins().stream()
        .map(u -> new UserResponse(u.username(), u.createdAt()))
        .toList();
  }

  @POST
  public RestResponse<UserResponse> create(@Valid CreateUserRequest body) {
    var created = service.createAdmin(new CreateAdminCommand(body.username(), body.password()));
    return RestResponse.ResponseBuilder.create(
            RestResponse.Status.CREATED, new UserResponse(created.username(), created.createdAt()))
        .build();
  }
}
