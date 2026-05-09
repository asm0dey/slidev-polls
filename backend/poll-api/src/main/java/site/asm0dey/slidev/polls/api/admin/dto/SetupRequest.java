package site.asm0dey.slidev.polls.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SetupRequest(
    @NotBlank @Pattern(regexp = "^[a-z0-9_-]{3,64}$") String username,
    @NotBlank @Size(min = 12) String password,
    @NotBlank @Size(min = 1, max = 100) String displayName) {}
