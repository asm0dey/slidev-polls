package site.asm0dey.slidev.polls.api.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record AddCollaboratorRequest(@NotBlank String username) {}
