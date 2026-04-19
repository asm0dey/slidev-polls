package site.asm0dey.slidev.polls.api.public_.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/polls/{slug}/votes}. The {@code voterToken} field is carried
 * for OpenAPI-schema compatibility, but the server treats the {@code sp_voter} cookie as
 * authoritative per the tasks.md clarification on voter identity (T086) — the body value is used
 * only as a one-time seed when no cookie is present.
 *
 * <p>Unknown top-level fields are tolerated ({@code fail-on-unknown-properties: false} in {@code
 * application.yml}) so that {@code @TS-027} extra fields like {@code email}/{@code name} never
 * reach the database.
 */
public record VoteRequest(@NotNull UUID optionId, String voterToken) {}
