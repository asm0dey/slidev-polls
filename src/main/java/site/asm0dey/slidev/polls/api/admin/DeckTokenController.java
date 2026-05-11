package site.asm0dey.slidev.polls.api.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.admin.dto.DeckTokenDto;
import site.asm0dey.slidev.polls.api.admin.dto.DeckTokenMintedDto;
import site.asm0dey.slidev.polls.api.admin.dto.MintDeckTokenRequest;
import site.asm0dey.slidev.polls.core.service.DeckTokenService;

/**
 * Presenter-facing deck-token endpoints. Ownership is enforced inside {@link DeckTokenService} so
 * the controller stays a thin HTTP adapter; all mutation paths are behind the {@code /api/admin/**}
 * session-auth gate configured in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/admin/polls/{pollId}/deck-tokens")
public class DeckTokenController {

  private final DeckTokenService service;

  public DeckTokenController(DeckTokenService service) {
    this.service = service;
  }

  @GetMapping
  public List<DeckTokenDto> list(
      @PathVariable UUID pollId, @AuthenticationPrincipal UserDetails presenter) {
    return service.list(pollId, presenter.getUsername()).stream().map(DeckTokenDto::from).toList();
  }

  @PostMapping
  public ResponseEntity<DeckTokenMintedDto> mint(
      @PathVariable UUID pollId,
      @RequestBody(required = false) MintDeckTokenRequest body,
      @AuthenticationPrincipal UserDetails presenter) {
    String label = body == null ? null : body.label();
    DeckTokenService.Minted minted = service.mint(pollId, presenter.getUsername(), label);
    return ResponseEntity.status(HttpStatus.CREATED).body(DeckTokenMintedDto.from(minted));
  }

  @DeleteMapping("/{tokenId}")
  public ResponseEntity<Void> revoke(
      @PathVariable UUID pollId,
      @PathVariable UUID tokenId,
      @AuthenticationPrincipal UserDetails presenter) {
    service.revoke(pollId, tokenId, presenter.getUsername());
    return ResponseEntity.noContent().build();
  }
}
