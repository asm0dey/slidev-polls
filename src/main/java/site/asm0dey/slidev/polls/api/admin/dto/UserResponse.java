package site.asm0dey.slidev.polls.api.admin.dto;

import java.time.Instant;

public record UserResponse(String username, Instant createdAt, boolean blocked) {}
