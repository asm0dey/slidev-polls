package site.asm0dey.slidev.polls.api.pub.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for a successful {@code POST /api/polls/{slug}/votes}. Mirrors {@code VoteAccepted}
 * in {@code openapi.yaml}: just the vote id and the server-side recorded timestamp — no voter_token
 * echo, no PII.
 */
public record VoteAccepted(UUID voteId, Instant recordedAt) {}
