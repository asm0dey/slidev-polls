package site.asm0dey.slidev.polls.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.asm0dey.slidev.polls.api.admin.dto.ActivateQuestionRequest;
import site.asm0dey.slidev.polls.api.admin.dto.CreatePollRequest;
import site.asm0dey.slidev.polls.api.admin.dto.PollDetailDto;
import site.asm0dey.slidev.polls.api.admin.dto.PollDto;
import site.asm0dey.slidev.polls.api.admin.dto.PollStyleDto;
import site.asm0dey.slidev.polls.api.admin.dto.UpdatePollRequest;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Presenter-authored poll lifecycle as exposed over HTTP. Every endpoint delegates straight through
 * to {@link PollService} — the controller is a thin boundary concerned only with Spring MVC wiring,
 * DTO mapping, and pulling the authenticated presenter's username from {@link Authentication}.
 *
 * <p>FR-001/ @TS-040/ @TS-041 ownership enforcement lives on the service's {@code ForOwner}
 * methods; {@code NotOwnerException} comes back through {@link
 * site.asm0dey.slidev.polls.api.error.GlobalExceptionHandler} as 403 {@code FORBIDDEN}. Slug
 * validation is similarly delegated — {@code SLUG_INVALID}, {@code SLUG_RESERVED}, {@code
 * SLUG_TAKEN} come out of the service as typed exceptions and land on the caller with the right
 * code, matching the OpenAPI 409 responses.
 *
 * <p>{@code publicUrlBase} is assembled from the request at response time via {@link
 * PublicUrlBase#of(HttpServletRequest)} so the join link always reflects the reverse-proxy host the
 * presenter actually hit; no config property is threaded through the call.
 */
@RestController
@RequestMapping("/api/admin/polls")
public class PollController {

  private final PollService pollService;

  public PollController(PollService pollService) {
    this.pollService = pollService;
  }

  @GetMapping
  public List<PollDto> list(Authentication authentication, HttpServletRequest request) {
    String owner = owner(authentication);
    String base = PublicUrlBase.of(request);
    return pollService.listForOwner(owner).stream().map(p -> PollDto.from(p, base)).toList();
  }

  @PostMapping
  public ResponseEntity<PollDetailDto> create(
      @Valid @RequestBody CreatePollRequest body,
      Authentication authentication,
      HttpServletRequest request) {
    Poll created = pollService.create(owner(authentication), body.toCommand());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(PollDetailDto.from(created, PublicUrlBase.of(request)));
  }

  @GetMapping("/{pollId}")
  public PollDetailDto get(
      @PathVariable UUID pollId, Authentication authentication, HttpServletRequest request) {
    Poll poll = pollService.getForOwner(pollId, owner(authentication));
    return PollDetailDto.from(poll, PublicUrlBase.of(request));
  }

  @PatchMapping("/{pollId}")
  public PollDetailDto update(
      @PathVariable UUID pollId,
      @Valid @RequestBody UpdatePollRequest body,
      Authentication authentication,
      HttpServletRequest request) {
    Poll updated = pollService.updateForOwner(pollId, owner(authentication), body.toCommand());
    return PollDetailDto.from(updated, PublicUrlBase.of(request));
  }

  @PostMapping("/{pollId}/clone")
  public ResponseEntity<PollDetailDto> clone(
      @PathVariable UUID pollId, Authentication authentication, HttpServletRequest request) {
    Poll cloned = pollService.cloneForOwner(pollId, owner(authentication));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(PollDetailDto.from(cloned, PublicUrlBase.of(request)));
  }

  @PostMapping("/{pollId}/votes:clear")
  public PollDetailDto clearVotes(
      @PathVariable UUID pollId, Authentication authentication, HttpServletRequest request) {
    Poll after = pollService.clearVotesForOwner(pollId, owner(authentication));
    return PollDetailDto.from(after, PublicUrlBase.of(request));
  }

  @DeleteMapping("/{pollId}")
  public ResponseEntity<Void> delete(@PathVariable UUID pollId, Authentication authentication) {
    pollService.deleteForOwner(pollId, owner(authentication));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{pollId}/open")
  public PollDetailDto open(
      @PathVariable UUID pollId,
      @Valid @RequestBody ActivateQuestionRequest body,
      Authentication authentication,
      HttpServletRequest request) {
    Poll after =
        pollService.activateQuestionForOwner(pollId, owner(authentication), body.questionId());
    return PollDetailDto.from(after, PublicUrlBase.of(request));
  }

  @PostMapping("/{pollId}/close")
  public PollDetailDto close(
      @PathVariable UUID pollId, Authentication authentication, HttpServletRequest request) {
    Poll after = pollService.closeActiveQuestionForOwner(pollId, owner(authentication));
    return PollDetailDto.from(after, PublicUrlBase.of(request));
  }

  @PutMapping("/{pollId}/style")
  public PollDetailDto style(
      @PathVariable UUID pollId,
      @RequestBody PollStyleDto body,
      Authentication authentication,
      HttpServletRequest request) {
    Poll after =
        pollService.updateStyleForOwner(
            pollId, owner(authentication), body == null ? java.util.Map.of() : body.toMap());
    return PollDetailDto.from(after, PublicUrlBase.of(request));
  }

  private static String owner(Authentication authentication) {
    if (authentication == null || authentication.getName() == null) {
      // Security config should have rejected an unauthenticated request before this point; if we
      // ever get here it means the filter chain or security config drifted — surface loudly rather
      // than silently mutate.
      throw new IllegalStateException("no authenticated presenter on the request");
    }
    return authentication.getName();
  }
}
