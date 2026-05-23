package site.asm0dey.slidev.polls.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank String currentPassword, @NotBlank @Size(min = 12) String newPassword) {}
