package site.asm0dey.slidev.polls.core.slug;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Mirrors @TS-012 (slug-management.feature): the seven reserved words that MUST NOT be allocatable
 * as poll slugs. The empty string is intentionally NOT in the reserved list — it fails the length
 * check in {@link SlugValidator} before the reserved check ever runs (see tasks.md clarification
 * 2026-04-19).
 */
class ReservedSlugsTest {

  // @TS-012 — reserved slugs
  @ParameterizedTest
  @ValueSource(strings = {"admin", "api", "assets", "static", "j", "login", "logout"})
  void flags_reserved_words(String slug) {
    // When asked about any of the seven reserved SPA / API route stems
    // Then the reserved-slug registry reports them as taken
    assertThat(ReservedSlugs.isReserved(slug)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADMIN", "Api", "aSSets", "STATIC", "Login"})
  void reserved_check_is_case_insensitive(String slug) {
    // Given a reserved word in mixed case
    // When checked
    // Then it is still reserved — callers should not be able to sneak a
    // backoffice-style slug past the check by uppercasing it.
    assertThat(ReservedSlugs.isReserved(slug)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"my-talk", "quickstart-demo", "apis", "adminfoo", "alogin", "logouts", "slidev"})
  void allows_non_reserved_slugs(String slug) {
    // When asked about a slug that is not one of the reserved words
    // Then the reserved-slug registry reports it allowed (match must be exact,
    // not a substring / prefix).
    assertThat(ReservedSlugs.isReserved(slug)).isFalse();
  }

  @Test
  void empty_string_is_not_reserved() {
    // Given the clarification dated 2026-04-19: the empty string is rejected
    // by SlugValidator on length grounds, so ReservedSlugs must not also claim
    // to own the empty string (that would make it look like a reserved word in
    // error messages / Problem codes).
    // When asked about ""
    // Then it is reported NOT reserved (SlugValidator catches it first).
    assertThat(ReservedSlugs.isReserved("")).isFalse();
  }

  @Test
  void null_is_not_reserved() {
    // When asked about a null reference
    // Then the check returns false rather than throwing — callers validate
    // the slug format first, and a null argument here means "no slug
    // supplied", which the reserved registry should simply step out of.
    assertThat(ReservedSlugs.isReserved(null)).isFalse();
  }
}
