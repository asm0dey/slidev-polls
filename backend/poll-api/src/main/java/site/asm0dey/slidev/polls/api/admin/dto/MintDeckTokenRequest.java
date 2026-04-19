package site.asm0dey.slidev.polls.api.admin.dto;

/** Body for {@code POST /api/admin/polls/{pollId}/deck-tokens}. Label is optional. */
public record MintDeckTokenRequest(String label) {}
