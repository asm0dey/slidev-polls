package site.asm0dey.slidev.polls.core.slug;

import java.util.regex.Pattern;

public final class SlugValidator {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 40;
    public static final Pattern PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private SlugValidator() {
    }

    public static boolean isValidFormat(String slug) {
        if (slug == null) {
            return false;
        }
        int len = slug.length();
        if (len < MIN_LENGTH || len > MAX_LENGTH) {
            return false;
        }
        return PATTERN.matcher(slug).matches();
    }
}
