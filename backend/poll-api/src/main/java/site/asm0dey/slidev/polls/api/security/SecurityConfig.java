package site.asm0dey.slidev.polls.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Servlet security for the poll-api. The gate is narrow: only {@code /api/admin/**} requires an
 * authenticated session. Everything else — the voter SPA shell ({@code /}, {@code /{slug}}), the
 * backoffice SPA shell ({@code /admin/} — routes under it are gated by the {@code /api/admin/**}
 * calls they make), the public poll API, and the deck-token endpoints — is open at the filter
 * chain; the deck-token endpoints have their own filter wired in T117.
 *
 * <p>401 / 403 are emitted as {@link site.asm0dey.slidev.polls.api.error.Problem} JSON via {@link
 * ProblemAuthenticationEntryPoint} / {@link ProblemAccessDeniedHandler} so every failure on a
 * guarded route carries the same envelope the rest of the API uses (@TS-001, @TS-042).
 *
 * <p>CSRF is configured to match the JSON login flow of the backoffice SPA: the CSRF token lives in
 * a readable {@code XSRF-TOKEN} cookie that the SPA echoes on state-changing requests under {@code
 * /api/admin/**}. The public vote/ stream paths are explicitly ignored so the voter, which never
 * speaks to the session, does not have to mint a token — respondent zero-friction is Principle II.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  AuthenticationManager authenticationManager(
      UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    // Explicit ProviderManager wiring so AdminAuthController can depend on a concrete
    // AuthenticationManager bean; Spring Boot 4's auto-configuration does not publish one by
    // default once a UserDetailsService bean of our own is in play.
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(provider);
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ProblemAuthenticationEntryPoint entryPoint,
      ProblemAccessDeniedHandler accessDeniedHandler,
      DeckTokenAuthenticationFilter deckTokenFilter,
      PerPollCorsConfigurationSource corsSource)
      throws Exception {
    // CSRF tokens live in a cookie the backoffice SPA can read (CookieCsrfTokenRepository
    // withHttpOnlyFalse), so a JSON POST from the SPA can echo them on a header. The raw-value
    // handler avoids the XOR scheme that confuses simple fetch clients — the cookie value and the
    // header value are identical (per Spring Security 7 docs).
    CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
    CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
    csrfHandler.setCsrfRequestAttributeName(null);

    http.cors(cors -> cors.configurationSource(corsSource))
        .authorizeHttpRequests(
            auth ->
                auth
                    // Login must be reachable before the caller holds a session.
                    .requestMatchers(HttpMethod.POST, "/api/admin/login")
                    .permitAll()
                    // Everything else under /api/admin/** requires authentication.
                    .requestMatchers("/api/admin/**")
                    .authenticated()
                    // Deck login is permitAll — the caller has no deck token yet (BUG-002). The
                    // controller calls AuthenticationManager itself.
                    .requestMatchers(HttpMethod.POST, "/api/deck/auth/login")
                    .permitAll()
                    // SPA shells and public surfaces are open at the filter chain.
                    .requestMatchers("/", "/admin/", "/admin/**")
                    .permitAll()
                    .requestMatchers("/api/polls/**", "/api/public/**")
                    .permitAll()
                    // Deck endpoints require the DECK authority the DeckTokenAuthenticationFilter
                    // attaches on a valid X-Deck-Token header (@TS-053). Without the authority
                    // the request falls through to the ProblemAuthenticationEntryPoint which
                    // emits DECK_TOKEN_INVALID (the entry point reads from the request path).
                    .requestMatchers("/api/deck/**")
                    .hasAuthority(DeckPrincipal.ROLE)
                    // Everything else (SPA static, catch-all, voter slug route) is open too — the
                    // SpaForwardingConfig (T087) does the actual routing; we just don't gate it.
                    .anyRequest()
                    .permitAll())
        .addFilterBefore(deckTokenFilter, UsernamePasswordAuthenticationFilter.class)
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfRepo)
                    .csrfTokenRequestHandler(csrfHandler)
                    // The voter never holds a session, so its state-changing endpoints are CSRF-
                    // exempt. Admin login is exempt too: the caller has no session yet, so there's
                    // no CSRF surface to protect.
                    .ignoringRequestMatchers(
                        "/api/polls/**", "/api/public/**", "/api/deck/**", "/api/admin/login"))
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .logout(Customizer.withDefaults());

    return http.build();
  }
}
