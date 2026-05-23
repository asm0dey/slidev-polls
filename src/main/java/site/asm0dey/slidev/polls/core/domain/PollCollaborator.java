package site.asm0dey.slidev.polls.core.domain;

import java.time.Instant;

/**
 * A presenter granted full co-owner edit rights on a poll (everything but owner-reserved actions).
 */
public record PollCollaborator(String username, Instant createdAt) {}
