package ai.msrv.gaterook.core.secrets;

import ai.msrv.gaterook.connector.api.SecretResolver;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * One decrypted value handed to a connector for one call (report 12 §6 "Process memory"): live {@code byte[]} and
 * {@code char[]} views, zeroed by {@link #close()}; {@code toString()} never shows it, and Jackson refuses to
 * serialize it rather than writing {@code {}}.
 */
@JsonSerialize(using = LocalSecretValue.Refuse.class)
final class LocalSecretValue implements SecretResolver.Secret {

  private final byte[] bytes;
  private final char[] chars;
  private final String version;
  private volatile boolean closed;

  /** Takes ownership of {@code bytes}. */
  LocalSecretValue(byte[] bytes, String version) {
    this.bytes = bytes;
    this.chars = decode(bytes);
    this.version = version;
  }

  /** A value that is text already (the password of a login). */
  LocalSecretValue(char[] chars, String version) {
    this.chars = chars;
    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
    this.bytes = new byte[encoded.remaining()];
    encoded.get(this.bytes);
    if (encoded.hasArray()) {
      Arrays.fill(encoded.array(), (byte) 0);
    }
    this.version = version;
  }

  @Override
  public char[] chars() {
    check();
    return chars;
  }

  @Override
  public byte[] bytes() {
    check();
    return bytes;
  }

  @Override
  public String unsafeString() {
    check();
    return new String(chars);
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public Optional<Instant> notAfter() {
    return Optional.empty();
  }

  @Override
  public void close() {
    closed = true;
    Arrays.fill(bytes, (byte) 0);
    Arrays.fill(chars, '\0');
  }

  @Override
  public String toString() {
    return "‹secret›";
  }

  private void check() {
    if (closed) {
      throw new IllegalStateException("This secret was closed when its call ended");
    }
  }

  private static char[] decode(byte[] bytes) {
    try {
      CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
      char[] out = new char[decoded.remaining()];
      decoded.get(out);
      if (decoded.hasArray()) {
        Arrays.fill(decoded.array(), '\0');
      }
      return out;
    } catch (CharacterCodingException e) {
      // A binary secret: no text view.
      return new char[0];
    }
  }

  /** Serializing a secret is a bug: fail loudly instead of writing {@code {}}. */
  static final class Refuse extends ValueSerializer<Object> {
    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
      throw new IllegalStateException("Secrets are never serialized");
    }
  }
}
