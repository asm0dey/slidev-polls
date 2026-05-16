package site.asm0dey.slidev.polls.api.pub.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/polls/{slug}/votes}. The {@code voterToken} field is carried
 * for OpenAPI-schema compatibility, but the server treats the {@code sp_voter} cookie as
 * authoritative per the tasks.md clarification on voter identity (T086) — the body value is used
 * only as a one-time seed when no cookie is present.
 *
 * <p>{@code optionIds} is the canonical ballot shape: a list of zero-or-more option ids on the
 * currently-active question. An empty list represents an abstention and is accepted only when the
 * active question's {@code minSelections} is {@code 0}; arity bounds (and the single-choice ⇒
 * exactly-one-id contract) are enforced server-side by {@code VoteService.recordVote}. Legacy
 * clients sending {@code optionId} are explicitly rejected: Jackson's {@code
 * fail-on-unknown-properties: false} swallows the field, leaving {@code optionIds} null, and the
 * {@code @NotNull} constraint surfaces as a 400 {@code VALIDATION_FAILED}.
 *
 * <p>Unknown top-level fields are tolerated ({@code fail-on-unknown-properties: false} in {@code
 * application.yml}) so that {@code @TS-027} extra fields like {@code email}/{@code name} never
 * reach the database.
 */
public record VoteRequest(@NotNull List<UUID> optionIds, String voterToken) {}
