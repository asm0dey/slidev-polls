package site.asm0dey.slidev.polls.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.DeckToken;
import site.asm0dey.slidev.polls.core.error.NotFoundException;

/**
 * Lifecycle for presenter-minted deck tokens (FR-019). Mint produces a random 32-byte bearer
 * encoded as URL-safe base64; only the SHA-256 hash is persisted ({@code @TS-056}). The returned
 * {@link Minted#plaintext} is the one and only time the bearer is visible — subsequent reads come
 * from {@link #list(UUID, String)} which returns hash-only rows.
 *
 * <p>All write and read paths check poll ownership ({@code @TS-057}); the deck-auth filter path
 * (see {@link #resolveLive(String)}) bypasses ownership by design — it is authorised by the bearer
 * itself.
 */
@ApplicationScoped
public class DeckTokenService {

  private static final SecureRandom RNG = new SecureRandom();
  private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

  private final DeckTokenRepository repository;
  private final PollRepository pollRepository;

  public DeckTokenService(DeckTokenRepository repository, PollRepository pollRepository) {
    this.repository = repository;
    this.pollRepository = pollRepository;
  }

  /**
   * Mint a new token for {@code pollId}. Caller MUST ensure {@code ownerUsername} owns the poll —
   * the service performs the check here, not at the controller, so unit tests fail closed.
   */
  @Transactional
  public Minted mint(UUID pollId, String ownerUsername, String label) {
    requireOwner(pollId, ownerUsername);
    byte[] random = new byte[32];
    RNG.nextBytes(random);
    String plaintext = BASE64.encodeToString(random);
    String hash = sha256(plaintext);
    DeckToken saved =
        repository.insert(
            new DeckToken(UUID.randomUUID(), pollId, hash, label, Instant.now(), null));
    return new Minted(saved, plaintext);
  }

  @Transactional
  public List<DeckToken> list(UUID pollId, String ownerUsername) {
    requireOwner(pollId, ownerUsername);
    return repository.findByPoll(pollId);
  }

  @Transactional
  public DeckToken revoke(UUID pollId, UUID tokenId, String ownerUsername) {
    requireOwner(pollId, ownerUsername);
    DeckToken existing =
        repository.findById(tokenId).orElseThrow(() -> new NotFoundException(tokenId.toString()));
    if (!existing.pollId().equals(pollId)) {
      // Mismatched token id / poll id in the URL; treat as not-found rather than mismatch so a
      // guessed tokenId does not leak the fact that it exists on some other poll.
      throw new NotFoundException(tokenId.toString());
    }
    if (existing.revokedAt() != null) {
      return existing;
    }
    return repository.revoke(tokenId);
  }

  /** Look up a live (non-revoked) token by plaintext. Used by the deck-auth filter. */
  @Transactional
  public Optional<DeckToken> resolveLive(String plaintext) {
    if (plaintext == null || plaintext.isBlank()) {
      return Optional.empty();
    }
    return repository.findLiveByHash(sha256(plaintext));
  }

  private void requireOwner(UUID pollId, String ownerUsername) {
    pollRepository
        .findById(pollId)
        .filter(p -> p.ownerUsername().equals(ownerUsername))
        .orElseThrow(() -> new NotFoundException(pollId.toString()));
  }

  static String sha256(String plaintext) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
      // Hex-encode for a stable, column-safe representation; base64 would also work but hex is
      // the convention we put in the V4 migration's comment and matches the unique-index check.
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256 is required to be present on every JRE per JLS, so this is unreachable.
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  /** Tuple returned by {@link #mint}: the persisted row plus the one-time plaintext. */
  public record Minted(DeckToken token, String plaintext) {}
}
