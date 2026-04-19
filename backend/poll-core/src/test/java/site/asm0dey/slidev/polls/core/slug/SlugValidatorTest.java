package site.asm0dey.slidev.polls.core.slug;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Mirrors @TS-011 (slug-management.feature) at the unit level: the rejected slug formats named
 * there must surface as {@code false} from {@link SlugValidator#isValidFormat}. The controller /
 * service layer is responsible for mapping that to the HTTP-level {@code SLUG_INVALID} Problem
 * code.
 */
class SlugValidatorTest {

  // @TS-011 — invalid slug formats
  @ParameterizedTest
  @ValueSource(
      strings = {
        // Too short (< 3 chars)
        "Ab",
        "ab",
        // Leading / trailing / consecutive hyphens
        "-leading",
        "trailing-",
        "double--dash",
        // Uppercase is not kebab-case
        "UPPER",
        // Whitespace
        "has space",
        // Exceeds 40-character cap
        "way-too-long-slug-exceeding-forty-chars-limit"
      })
  void rejects_invalid_formats(String slug) {
    // When asked to validate a slug that breaks the kebab-case / length contract
    // Then the validator reports it as not a valid format
    assertThat(SlugValidator.isValidFormat(slug)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "my-talk", "q1-demo", "ab1", "slidev-polls-foundation"})
  void accepts_valid_formats(String slug) {
    // When asked to validate a slug that obeys kebab-case and length
    // Then the validator reports it valid
    assertThat(SlugValidator.isValidFormat(slug)).isTrue();
  }

  @Test
  void rejects_null_slug() {
    // When asked about a null reference
    // Then the validator treats it as invalid rather than throwing
    assertThat(SlugValidator.isValidFormat(null)).isFalse();
  }

  @Test
  void rejects_empty_slug_on_length_grounds() {
    // Given the empty string (length 0)
    // When validated
    // Then isValidFormat is false because minimum length is 3 — this is the
    // path the "empty is not a reserved slug" clarification relies on
    // (see ReservedSlugsTest#empty_string_is_not_reserved).
    assertThat(SlugValidator.isValidFormat("")).isFalse();
  }
}
