package site.asm0dey.slidev.polls.core.domain;

import java.time.Instant;

/**
 * Presenter account as projected by the service layer. The hash never leaves the persistence
 * boundary; controllers serialise UserResponse instead.
 */
public record AdminUser(String username, Instant createdAt) {}
