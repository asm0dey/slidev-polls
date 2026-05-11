package site.asm0dey.slidev.polls.api.deck.dto;

import jakarta.validation.constraints.NotBlank;

public record DeckLoginRequest(@NotBlank String username, @NotBlank String password) {}
