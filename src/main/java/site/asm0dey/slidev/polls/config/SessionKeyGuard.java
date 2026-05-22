package site.asm0dey.slidev.polls.config;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Fail-fast guard against shipping the baked-in placeholder session key to production. {@code
 * application.properties} defaults {@code quarkus.http.auth.session.encryption-key} to a public
 * placeholder so dev/test work out of the box; if that placeholder ever reaches a {@code prod}
 * launch it means {@code SP_SESSION_KEY} was not supplied and every admin session cookie would be
 * signed with a value anyone can read from the repo — a session-forgery hazard. This bean aborts
 * startup in that case with a clear message, and only in {@code prod}: dev and test keep using the
 * placeholder.
 */
@ApplicationScoped
public class SessionKeyGuard {

  /**
   * The baked-in placeholder from {@code application.properties}. Mirrors the {@code
   * ${SP_SESSION_KEY:...}} default; if the resolved key equals this in prod, the env var was unset.
   */
  static final String PLACEHOLDER = "change-me-32-bytes-minimum-length!!";

  @ConfigProperty(name = "quarkus.http.auth.session.encryption-key")
  String configuredKey;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.NORMAL && PLACEHOLDER.equals(configuredKey)) {
      throw new IllegalStateException(
          "quarkus.http.auth.session.encryption-key is still the baked-in placeholder in"
              + " production. Set the SP_SESSION_KEY environment variable to a strong, secret value"
              + " (>= 32 bytes) before starting in prod — admin sessions would otherwise be signed"
              + " with a publicly known key.");
    }
  }
}
