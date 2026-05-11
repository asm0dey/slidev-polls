package site.asm0dey.slidev.polls.api.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/admin/polls/{pollId}/open} and {@code POST
 * /api/deck/polls/{pollId}/activate}. Mirrors {@code ActivateQuestionRequest} in {@code
 * openapi.yaml}.
 */
public record ActivateQuestionRequest(@NotNull UUID questionId) {}
