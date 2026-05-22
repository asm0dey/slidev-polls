package site.asm0dey.slidev.polls.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Current authenticated account view returned by {@code GET /api/admin/account}.
 *
 * <p>{@code @JsonProperty("isAdmin")} is required because Jackson's classic bean-property
 * introspection would strip the {@code is} prefix from the accessor {@code isAdmin()} and emit the
 * field as {@code admin}. The explicit annotation pins the wire name to {@code isAdmin}.
 */
public record AccountResponse(String username, @JsonProperty("isAdmin") boolean isAdmin) {}
