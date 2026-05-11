package site.asm0dey.slidev.polls.api.pub;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.ResponseCookie;

/**
 * Server-side ownership of the {@code sp_voter} cookie. The tasks.md voter-identity clarification
 * says the server is authoritative for this identifier — the client never writes it, localStorage
 * only caches per-slug {@code alreadyVoted} booleans (T086, T091).
 *
 * <p>The cookie is {@code HttpOnly}, {@code SameSite=Lax}, and {@code Secure} when the request
 * arrived over HTTPS (reverse-proxy-terminated TLS in prod, plain HTTP in MockMvc tests). Its value
 * is a freshly-generated {@link UUID} — no PII, no device fingerprint — so {@code @TS-046}'s "no
 * personal information" assertion holds by construction.
 */
public final class VoterTokenCookie {

  public static final String NAME = "sp_voter";
  // A year is long enough that the typical attendee keeps the same identity across multiple
  // talks at a conference without the presenter seeing duplicate first-time voters, and short
  // enough that a reset-by-idle path does eventually kick in.
  private static final Duration MAX_AGE = Duration.ofDays(365);

  private VoterTokenCookie() {}

  /** Read the current {@code sp_voter} value, or {@code null} when no cookie is present. */
  public static String read(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (NAME.equals(cookie.getName())) {
        String value = cookie.getValue();
        if (value != null && !value.isBlank()) {
          return value;
        }
      }
    }
    return null;
  }

  /**
   * Resolve the voter token for this request: the existing cookie when present, or a freshly minted
   * UUID with a matching Set-Cookie header in {@link Resolution#setCookieHeader()}. Controllers
   * attach the header via {@code ResponseEntity.header(HttpHeaders.SET_COOKIE, ...)} when the
   * resolution reports a fresh cookie — an existing cookie returns a resolution with a {@code null}
   * header so no spurious Set-Cookie is sent.
   */
  public static Resolution readOrIssue(HttpServletRequest request) {
    String existing = read(request);
    if (existing != null) {
      return new Resolution(existing, null);
    }
    String minted = UUID.randomUUID().toString();
    return new Resolution(minted, buildSetCookieHeader(minted, request.isSecure()));
  }

  private static String buildSetCookieHeader(String value, boolean secure) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .sameSite("Lax")
        .secure(secure)
        .path("/")
        .maxAge(MAX_AGE)
        .build()
        .toString();
  }

  /** {@code setCookieHeader} is {@code null} when the request already carried a valid cookie. */
  public record Resolution(String token, String setCookieHeader) {}
}
