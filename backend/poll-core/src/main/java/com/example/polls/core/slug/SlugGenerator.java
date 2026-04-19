package com.example.polls.core.slug;

import java.text.Normalizer;
import java.util.Locale;

public final class SlugGenerator {

    private SlugGenerator() {
    }

    /**
     * Derive a base slug from a human title. The result is lowercase, contains
     * only {@code [a-z0-9-]}, has no leading/trailing or consecutive hyphens,
     * and is trimmed to {@link SlugValidator#MAX_LENGTH}. Collision handling
     * and reserved-slug rejection are the caller's responsibility.
     */
    public static String fromTitle(String title) {
        if (title == null) {
            return "";
        }
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        String lower = normalized.toLowerCase(Locale.ROOT);
        String dashed = lower.replaceAll("[^a-z0-9]+", "-");
        String trimmed = dashed.replaceAll("^-+", "").replaceAll("-+$", "");
        if (trimmed.length() > SlugValidator.MAX_LENGTH) {
            trimmed = trimmed.substring(0, SlugValidator.MAX_LENGTH).replaceAll("-+$", "");
        }
        return trimmed;
    }
}
