package site.asm0dey.slidev.polls.api.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Encrypts and verifies the {@code SP_SESSION} admin session cookie. Replaces the servlet {@code
 * HttpSession} the Spring stack relied on: the session state is the authenticated username plus an
 * absolute expiry, sealed into a self-contained, tamper-proof cookie value so the API stays
 * stateless (no server-side session store to migrate or scale).
 *
 * <p>The cookie value is {@code base64url(iv ‖ ciphertext ‖ gcmTag)} where the plaintext is {@code
 * "<username>\n<epochMillisExpiry>"} sealed with AES-256-GCM. GCM's authentication tag means any
 * mutation of the value fails decryption, so {@link #verify} cannot be tricked into accepting a
 * forged username. The 256-bit key is derived as {@code SHA-256(configuredKey)} so the operator can
 * supply any sufficiently-random string via {@code quarkus.http.auth.session.encryption-key}.
 */
@ApplicationScoped
public class AdminSessionCookie {

  /** Name of the session cookie. */
  public static final String COOKIE_NAME = "SP_SESSION";

  private static final int IV_LENGTH = 12; // 96-bit nonce, the GCM-recommended size.
  private static final int TAG_LENGTH_BITS = 128;
  private static final SecureRandom RNG = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();

  private final SecretKeySpec key;

  public AdminSessionCookie(
      @ConfigProperty(name = "quarkus.http.auth.session.encryption-key") String configuredKey) {
    this.key = deriveKey(configuredKey);
  }

  private static SecretKeySpec deriveKey(String configuredKey) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(configuredKey.getBytes(StandardCharsets.UTF_8));
      return new SecretKeySpec(digest, "AES");
    } catch (GeneralSecurityException ex) {
      // SHA-256 is mandated on every JRE, so this is unreachable.
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  /**
   * Seal {@code username} with an absolute expiry {@code now + ttl} into the encrypted cookie
   * value. Returns only the value; cookie attributes are applied by {@link #setCookieHeader}.
   */
  public String mint(String username, Duration ttl) {
    long expiry = Instant.now().plus(ttl).toEpochMilli();
    byte[] plaintext = (username + "\n" + expiry).getBytes(StandardCharsets.UTF_8);
    try {
      byte[] iv = new byte[IV_LENGTH];
      RNG.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext);
      byte[] combined = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
      return B64.encodeToString(combined);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("AES-GCM encryption failed", ex);
    }
  }

  /**
   * Decrypt and validate {@code cookieValue}. Returns the sealed username when the value decrypts
   * cleanly (authentication tag intact) and the absolute expiry is still in the future; empty on
   * any tampering, malformed value, or expiry.
   */
  public Optional<String> verify(String cookieValue) {
    if (cookieValue == null || cookieValue.isBlank()) {
      return Optional.empty();
    }
    try {
      byte[] combined = B64D.decode(cookieValue);
      if (combined.length <= IV_LENGTH) {
        return Optional.empty();
      }
      byte[] iv = new byte[IV_LENGTH];
      System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] plaintext = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
      String decoded = new String(plaintext, StandardCharsets.UTF_8);
      int sep = decoded.lastIndexOf('\n');
      if (sep < 0) {
        return Optional.empty();
      }
      String username = decoded.substring(0, sep);
      long expiry = Long.parseLong(decoded.substring(sep + 1));
      if (Instant.now().toEpochMilli() >= expiry) {
        return Optional.empty();
      }
      return Optional.of(username);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      // AEADBadTagException (tamper), malformed base64, or a bad expiry number — all reject.
      return Optional.empty();
    }
  }

  /**
   * Build the {@code Set-Cookie} header value that carries a freshly-minted session. {@code Secure}
   * is added only in prod (the caller passes {@code secure}); {@code HttpOnly} blocks JS access and
   * {@code SameSite=Lax} keeps the cookie off cross-site POSTs while still surviving top-level
   * navigations.
   */
  public String setCookieHeader(String cookieValue, Duration ttl, boolean secure) {
    StringBuilder sb = new StringBuilder();
    sb.append(COOKIE_NAME).append('=').append(cookieValue);
    sb.append("; Path=/");
    sb.append("; HttpOnly");
    sb.append("; SameSite=Lax");
    sb.append("; Max-Age=").append(ttl.toSeconds());
    if (secure) {
      sb.append("; Secure");
    }
    return sb.toString();
  }

  /** Build the {@code Set-Cookie} header value that clears the session ({@code Max-Age=0}). */
  public String clearCookieHeader(boolean secure) {
    StringBuilder sb = new StringBuilder();
    sb.append(COOKIE_NAME).append('=');
    sb.append("; Path=/");
    sb.append("; HttpOnly");
    sb.append("; SameSite=Lax");
    sb.append("; Max-Age=0");
    if (secure) {
      sb.append("; Secure");
    }
    return sb.toString();
  }
}
