package site.asm0dey.slidev.polls.api.admin.dto;

import java.time.Instant;

public record CollaboratorResponse(String username, Instant createdAt) {}
