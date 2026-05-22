package site.asm0dey.slidev.polls.api.admin;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import java.util.List;
import java.util.UUID;
import org.jboss.resteasy.reactive.RestResponse;
import site.asm0dey.slidev.polls.api.admin.dto.DeckTokenDto;
import site.asm0dey.slidev.polls.api.admin.dto.DeckTokenMintedDto;
import site.asm0dey.slidev.polls.api.admin.dto.MintDeckTokenRequest;
import site.asm0dey.slidev.polls.core.service.DeckTokenService;

/**
 * Presenter-facing deck-token endpoints. Ownership is enforced inside {@link DeckTokenService} so
 * the controller stays a thin HTTP adapter; all mutation paths are behind the {@code /api/admin/**}
 * session-auth gate (role {@code ADMIN}).
 */
@Path("/api/admin/polls/{pollId}/deck-tokens")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed("ADMIN")
public class DeckTokenController {

  private final DeckTokenService service;

  public DeckTokenController(DeckTokenService service) {
    this.service = service;
  }

  @GET
  public List<DeckTokenDto> list(
      @PathParam("pollId") UUID pollId, @Context SecurityIdentity identity) {
    return service.list(pollId, identity.getPrincipal().getName()).stream()
        .map(DeckTokenDto::from)
        .toList();
  }

  @POST
  public RestResponse<DeckTokenMintedDto> mint(
      @PathParam("pollId") UUID pollId,
      MintDeckTokenRequest body,
      @Context SecurityIdentity identity) {
    String label = body == null ? null : body.label();
    DeckTokenService.Minted minted = service.mint(pollId, identity.getPrincipal().getName(), label);
    return RestResponse.ResponseBuilder.create(
            RestResponse.Status.CREATED, DeckTokenMintedDto.from(minted))
        .build();
  }

  @DELETE
  @Path("/{tokenId}")
  public RestResponse<Void> revoke(
      @PathParam("pollId") UUID pollId,
      @PathParam("tokenId") UUID tokenId,
      @Context SecurityIdentity identity) {
    service.revoke(pollId, tokenId, identity.getPrincipal().getName());
    return RestResponse.noContent();
  }
}
