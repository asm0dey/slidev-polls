package site.asm0dey.slidev.polls.api.pub;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.RoutingContext;
import java.time.Duration;
import java.util.UUID;

/**
 * Server-side ownership of the {@code sp_voter} cookie. The tasks.md voter-identity clarification
 * says the server is authoritative for this identifier — the client never writes it, localStorage
 * only caches per-slug {@code alreadyVoted} booleans (T086, T091).
 *
 * <p>The cookie is {@code HttpOnly}, {@code SameSite=Lax}, and {@code Secure} when the request
 * arrived over HTTPS (reverse-proxy-terminated TLS in prod, plain HTTP in tests). Its value is a
 * freshly-generated {@link UUID} — no PII, no device fingerprint — so {@code @TS-046}'s "no
 * personal information" assertion holds by construction.
 */
public final class VoterTokenCookie {

  public static final String NAME = "sp_voter";
  // A year is long enough that the typical attendee keeps the same identity across multiple talks
  // at a conference without the presenter seeing duplicate first-time voters, and short enough that
  // a reset-by-idle path does eventually kick in.
  private static final Duration MAX_AGE = Duration.ofDays(365);

  private VoterTokenCookie() {}

  /** Read the current {@code sp_voter} value, or {@code null} when no cookie is present. */
  public static String read(RoutingContext ctx) {
    Cookie cookie = ctx.request().getCookie(NAME);
    if (cookie == null) {
      return null;
    }
    String value = cookie.getValue();
    return (value == null || value.isBlank()) ? null : value;
  }

  /**
   * Resolve the voter token for this request: the existing cookie when present, or a freshly minted
   * UUID with a matching Set-Cookie header in {@link Resolution#setCookieHeader()}. Controllers
   * attach the header on the response when the resolution reports a fresh cookie — an existing
   * cookie returns a resolution with a {@code null} header so no spurious Set-Cookie is sent.
   */
  public static Resolution readOrIssue(RoutingContext ctx) {
    String existing = read(ctx);
    if (existing != null) {
      return new Resolution(existing, null);
    }
    String minted = UUID.randomUUID().toString();
    return new Resolution(minted, buildSetCookieHeader(minted, ctx.request().isSSL()));
  }

  private static String buildSetCookieHeader(String value, boolean secure) {
    return Cookie.cookie(NAME, value)
        .setHttpOnly(true)
        .setSameSite(CookieSameSite.LAX)
        .setSecure(secure)
        .setPath("/")
        .setMaxAge(MAX_AGE.toSeconds())
        .encode();
  }

  /** {@code setCookieHeader} is {@code null} when the request already carried a valid cookie. */
  public record Resolution(String token, String setCookieHeader) {}
}
