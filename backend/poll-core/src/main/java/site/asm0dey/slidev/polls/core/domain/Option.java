package site.asm0dey.slidev.polls.core.domain;

import java.util.UUID;

/** A selectable answer on a {@link Question}. {@code position} orders options within the question. */
public record Option(UUID id, UUID questionId, String label, int position) {}
