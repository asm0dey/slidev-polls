package site.asm0dey.slidev.polls.api.admin;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.jboss.resteasy.reactive.RestResponse;
import site.asm0dey.slidev.polls.api.admin.dto.SetupRequest;
import site.asm0dey.slidev.polls.api.admin.dto.SetupStatusResponse;
import site.asm0dey.slidev.polls.api.admin.dto.UserResponse;
import site.asm0dey.slidev.polls.core.service.AdminUserService;
import site.asm0dey.slidev.polls.core.service.CreateAdminCommand;

/**
 * First-run bootstrap. Both endpoints are public on the filter chain — the gating happens in the
 * service: createInitialAdmin throws SetupLockedException once admin_user is non-empty, which the
 * DomainExceptionMappers map to 409 Problem(SETUP_LOCKED).
 */
@Path("/api/admin/setup")
@ApplicationScoped
@RunOnVirtualThread
public class AdminSetupController {

  private final AdminUserService service;

  public AdminSetupController(AdminUserService service) {
    this.service = service;
  }

  @GET
  @Path("/status")
  public SetupStatusResponse status() {
    return new SetupStatusResponse(service.isSetupRequired());
  }

  @POST
  public RestResponse<UserResponse> setup(@Valid SetupRequest body) {
    var created =
        service.createInitialAdmin(new CreateAdminCommand(body.username(), body.password()));
    return RestResponse.ResponseBuilder.create(
            RestResponse.Status.CREATED, new UserResponse(created.username(), created.createdAt()))
        .build();
  }
}
