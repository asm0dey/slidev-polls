package site.asm0dey.slidev.polls.api.admin.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Theme overrides persisted as jsonb on {@code polls.style}. Mirrors the {@code PollStyle} schema
 * in {@code openapi.yaml}: every field is optional, so the API accepts a partial object and callers
 * never have to supply all four. The record keeps the DTO boundary explicit while {@link #toMap()}
 * / {@link #fromMap(Map)} take the transport over to the service-layer {@code Map<String, Object>}
 * that {@code poll-core} speaks.
 */
public record PollStyleDto(
    String primaryColor,
    String accentColor,
    String backgroundColor,
    String fontFamily,
    String layout) {

  /** Produces a jsonb-friendly map; only non-null fields make it in. */
  public Map<String, Object> toMap() {
    Map<String, Object> out = new LinkedHashMap<>();
    if (primaryColor != null) out.put("primaryColor", primaryColor);
    if (accentColor != null) out.put("accentColor", accentColor);
    if (backgroundColor != null) out.put("backgroundColor", backgroundColor);
    if (fontFamily != null) out.put("fontFamily", fontFamily);
    if (layout != null) out.put("layout", layout);
    return out;
  }

  /**
   * Inverse of {@link #toMap()}. Unknown jsonb keys are ignored — the schema is presenter-owned.
   */
  public static PollStyleDto fromMap(Map<String, Object> style) {
    if (style == null || style.isEmpty()) {
      return new PollStyleDto(null, null, null, null, null);
    }
    return new PollStyleDto(
        asString(style.get("primaryColor")),
        asString(style.get("accentColor")),
        asString(style.get("backgroundColor")),
        asString(style.get("fontFamily")),
        asString(style.get("layout")));
  }

  private static String asString(Object value) {
    return value == null ? null : value.toString();
  }
}
