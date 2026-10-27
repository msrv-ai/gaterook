package ai.msrv.gaterook.plugin.protocol.host;

import ai.msrv.gaterook.connector.api.SecretResolver;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * A secret the core handed to a plugin for one call: live {@code char[]} and {@code byte[]} views, zeroed by
 * {@link #close()} (the host closes every one when the call ends, even if the plugin forgot). Never a {@code String}
 * unless the plugin asks for one through {@link #unsafeString()}.
 */
public final class WireSecret implements SecretResolver.Secret {

  private final char[] chars;
  private final byte[] bytes;
  private final String version;
  private final Instant notAfter;
  private volatile boolean closed;

  private WireSecret(char[] chars, byte[] bytes, String version, Instant notAfter) {
    this.chars = chars;
    this.bytes = bytes;
    this.version = version;
    this.notAfter = notAfter;
  }

  /** A text value; its UTF-8 bytes are the byte view. Takes ownership of {@code chars}. */
  public static WireSecret ofChars(char[] chars, String version, Instant notAfter) {
    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    if (encoded.hasArray()) {
      Arrays.fill(encoded.array(), (byte) 0);
    }
    return new WireSecret(chars, bytes, version, notAfter);
  }

  /** A binary value; the text view is its UTF-8 decoding, or empty when it isn't UTF-8. Takes ownership. */
  public static WireSecret ofBytes(byte[] bytes, String version, Instant notAfter) {
    char[] chars;
    try {
      CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
      chars = new char[decoded.remaining()];
      decoded.get(chars);
      if (decoded.hasArray()) {
        Arrays.fill(decoded.array(), '\0');
      }
    } catch (CharacterCodingException e) {
      chars = new char[0];
    }
    return new WireSecret(chars, bytes, version, notAfter);
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
    return chars.length > 0 ? new String(chars) : new String(bytes, StandardCharsets.UTF_8);
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public Optional<Instant> notAfter() {
    return Optional.ofNullable(notAfter);
  }

  @Override
  public void close() {
    closed = true;
    Arrays.fill(chars, '\0');
    Arrays.fill(bytes, (byte) 0);
  }

  private void check() {
    if (closed) {
      throw new IllegalStateException("The call ended: this secret is gone");
    }
  }

  @Override
  public String toString() {
    return "‹secret›";
  }
}
