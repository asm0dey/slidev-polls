package site.asm0dey.slidev.polls.core.error;

public final class InvalidOriginException extends RuntimeException {
  public InvalidOriginException(String origin) {
    super("invalid origin: " + origin);
  }
}
