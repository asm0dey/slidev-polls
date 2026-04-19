package site.asm0dey.slidev.polls.api.admin.dto;

import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Option;

/** Mirrors the {@code Option} schema in {@code openapi.yaml}. */
public record OptionDto(UUID id, String label, int position) {

  public static OptionDto from(Option option) {
    return new OptionDto(option.id(), option.label(), option.position());
  }
}
