package site.asm0dey.slidev.polls.api.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id password hasher backed directly by BouncyCastle's {@link Argon2BytesGenerator},
 * replacing Spring Security's {@code Argon2PasswordEncoder} during the Quarkus migration.
 *
 * <p>The emitted PHC string is byte-for-byte the layout Spring's {@code Argon2EncodingUtils}
 * produces — {@code $argon2id$v=19$m=65536,t=3,p=4$<b64salt>$<b64hash>} — with the salt and hash
 * Base64-encoded without padding using the standard (non-URL) alphabet. Verification re-reads the
 * memory/iteration/parallelism cost and the salt from the stored string and recomputes against the
 * embedded version, so rows written by the old Spring encoder validate unchanged.
 *
 * <p>New hashes use the OWASP "stronger" parameters: 64 MiB memory, 3 iterations, 4 lanes, a
 * 16-byte random salt and a 32-byte output, matching {@code new Argon2PasswordEncoder(16, 32, 4,
 * 65536, 3)} from the prior {@code SecurityConfig}.
 */
@ApplicationScoped
public class Argon2PasswordHasher {

  private static final int SALT_LENGTH = 16;
  private static final int HASH_LENGTH = 32;
  private static final int PARALLELISM = 4;
  private static final int MEMORY_KB = 65536;
  private static final int ITERATIONS = 3;

  private static final Base64.Encoder B64_ENCODER = Base64.getEncoder().withoutPadding();
  private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * Hashes {@code raw} with a fresh random salt and returns the self-describing PHC string. The
   * format is identical to Spring Security's {@code Argon2PasswordEncoder} output.
   */
  public String encode(String raw) {
    byte[] salt = new byte[SALT_LENGTH];
    secureRandom.nextBytes(salt);

    Argon2Parameters params =
        new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(PARALLELISM)
            .withMemoryAsKB(MEMORY_KB)
            .withIterations(ITERATIONS)
            .build();

    byte[] hash = new byte[HASH_LENGTH];
    Argon2BytesGenerator generator = new Argon2BytesGenerator();
    generator.init(params);
    generator.generateBytes(raw.toCharArray(), hash);

    return encode(hash, params);
  }

  /**
   * Recomputes the Argon2 hash of {@code raw} using the parameters and salt parsed from {@code
   * encoded} and compares it to the stored hash in constant time. Returns {@code false} (rather
   * than throwing) on a malformed encoding.
   */
  public boolean matches(String raw, String encoded) {
    if (raw == null || encoded == null) {
      return false;
    }
    Decoded decoded;
    try {
      decoded = decode(encoded);
    } catch (RuntimeException ex) {
      return false;
    }

    byte[] computed = new byte[decoded.hash.length];
    Argon2BytesGenerator generator = new Argon2BytesGenerator();
    generator.init(decoded.parameters);
    generator.generateBytes(raw.toCharArray(), computed);

    return constantTimeEquals(decoded.hash, computed);
  }

  private static String encode(byte[] hash, Argon2Parameters parameters) {
    StringBuilder sb = new StringBuilder();
    sb.append("$argon2id");
    sb.append("$v=")
        .append(parameters.getVersion())
        .append("$m=")
        .append(parameters.getMemory())
        .append(",t=")
        .append(parameters.getIterations())
        .append(",p=")
        .append(parameters.getLanes());
    sb.append("$").append(B64_ENCODER.encodeToString(parameters.getSalt()));
    sb.append("$").append(B64_ENCODER.encodeToString(hash));
    return sb.toString();
  }

  private static Decoded decode(String encoded) {
    String[] parts = encoded.split("\\$");
    if (parts.length < 4) {
      throw new IllegalArgumentException("Invalid encoded Argon2 hash");
    }
    int idx = 1;
    if (!"argon2id".equals(parts[idx++])) {
      throw new IllegalArgumentException("Unsupported Argon2 type: " + parts[1]);
    }

    Argon2Parameters.Builder builder = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id);
    if (parts[idx].startsWith("v=")) {
      builder.withVersion(Integer.parseInt(parts[idx].substring(2)));
      idx++;
    }

    String[] perf = parts[idx++].split(",");
    if (perf.length != 3) {
      throw new IllegalArgumentException("Invalid performance parameters");
    }
    if (!perf[0].startsWith("m=")) {
      throw new IllegalArgumentException("Invalid memory parameter");
    }
    builder.withMemoryAsKB(Integer.parseInt(perf[0].substring(2)));
    if (!perf[1].startsWith("t=")) {
      throw new IllegalArgumentException("Invalid iterations parameter");
    }
    builder.withIterations(Integer.parseInt(perf[1].substring(2)));
    if (!perf[2].startsWith("p=")) {
      throw new IllegalArgumentException("Invalid parallelism parameter");
    }
    builder.withParallelism(Integer.parseInt(perf[2].substring(2)));

    byte[] salt = B64_DECODER.decode(parts[idx++]);
    builder.withSalt(salt);
    byte[] hash = B64_DECODER.decode(parts[idx]);
    return new Decoded(hash, builder.build());
  }

  private static boolean constantTimeEquals(byte[] expected, byte[] actual) {
    if (expected.length != actual.length) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < expected.length; i++) {
      result |= expected[i] ^ actual[i];
    }
    return result == 0;
  }

  private record Decoded(byte[] hash, Argon2Parameters parameters) {}
}
