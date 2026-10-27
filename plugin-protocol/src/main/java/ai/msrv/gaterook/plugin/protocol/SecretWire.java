package ai.msrv.gaterook.plugin.protocol;

import ai.msrv.gaterook.plugin.protocol.host.WireSecret;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

/**
 * How a secret value crosses GPP/1 (report 16 §5): the core writes it from its {@code char[]} straight into the frame
 * and zeroes its copy, the host reads it from the frame straight into a {@code char[]} — no {@code String} of the
 * value on either side. {@code {"value": "…", "encoding": "utf8"|"base64", "version": "v3", "notAfter": "…"}};
 * {@code secrets/login} adds {@code username} and puts the value under {@code password}.
 */
public final class SecretWire {

  private SecretWire() {}

  /** A text value; {@code chars} is zeroed after the write. */
  public static JsonRpcPeer.RawResult text(char[] chars, String version, Instant notAfter) {
    return new Value(null, "value", chars, null, version, notAfter);
  }

  /** A binary value (a keystore, a keytab), Base64 on the wire; {@code bytes} is zeroed after the write. */
  public static JsonRpcPeer.RawResult binary(byte[] bytes, String version, Instant notAfter) {
    return new Value(null, "value", null, bytes, version, notAfter);
  }

  /** A login: a username (not secret) and a password, zeroed after the write. */
  public static JsonRpcPeer.RawResult login(String username, char[] password, String version, Instant notAfter) {
    return new Value(username, "password", password, null, version, notAfter);
  }

  /** What {@code secrets/get} answers, read into a {@link WireSecret}. */
  public static JsonRpcPeer.RawDecoder<WireSecret> secretDecoder() {
    return p -> read(p).secret();
  }

  /** What {@code secrets/login} answers. */
  public static JsonRpcPeer.RawDecoder<Login> loginDecoder() {
    return p -> {
      Read r = read(p);
      return new Login(r.username, r.secret());
    };
  }

  /** A login as read: the username and the password as a secret. */
  public record Login(String username, WireSecret password) {}

  private record Value(String username, String member, char[] chars, byte[] bytes, String version, Instant notAfter)
      implements JsonRpcPeer.RawResult {

    @Override
    public void write(JsonGenerator g) {
      g.writeStartObject();
      if (username != null) {
        g.writeName("username");
        g.writeString(username);
      }
      g.writeName(member);
      if (chars != null) {
        g.writeString(chars, 0, chars.length);
        g.writeName("encoding");
        g.writeString("utf8");
      } else {
        char[] encoded = base64(bytes);
        try {
          g.writeString(encoded, 0, encoded.length);
        } finally {
          Arrays.fill(encoded, '\0');
        }
        g.writeName("encoding");
        g.writeString("base64");
      }
      if (version != null) {
        g.writeName("version");
        g.writeString(version);
      }
      if (notAfter != null) {
        g.writeName("notAfter");
        g.writeString(notAfter.toString());
      }
      g.writeEndObject();
    }

    @Override
    public void dispose() {
      if (chars != null) {
        Arrays.fill(chars, '\0');
      }
      if (bytes != null) {
        Arrays.fill(bytes, (byte) 0);
      }
    }
  }

  private static final class Read {
    String username;
    char[] value;
    String encoding = "utf8";
    String version;
    Instant notAfter;

    WireSecret secret() {
      char[] v = value == null ? new char[0] : value;
      if ("base64".equals(encoding)) {
        byte[] bytes = decode(v);
        Arrays.fill(v, '\0');
        return WireSecret.ofBytes(bytes, version, notAfter);
      }
      return WireSecret.ofChars(v, version, notAfter);
    }
  }

  private static Read read(JsonParser p) {
    if (p.currentToken() != JsonToken.START_OBJECT) {
      throw new IllegalArgumentException("A secret answer is an object");
    }
    Read r = new Read();
    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
      String name = p.currentName();
      JsonToken t = p.nextToken();
      switch (name) {
        case "value", "password" -> {
          if (t == JsonToken.VALUE_STRING) {
            int offset = p.getStringOffset();
            r.value = Arrays.copyOfRange(p.getStringCharacters(), offset, offset + p.getStringLength());
          }
        }
        case "username" -> r.username = t == JsonToken.VALUE_STRING ? p.getString() : null;
        case "encoding" -> r.encoding = p.getString();
        case "version" -> r.version = t == JsonToken.VALUE_STRING ? p.getString() : null;
        case "notAfter" -> r.notAfter = t == JsonToken.VALUE_STRING ? Instant.parse(p.getString()) : null;
        default -> p.skipChildren();
      }
    }
    return r;
  }

  private static char[] base64(byte[] bytes) {
    byte[] encoded = Base64.getEncoder().encode(bytes);
    char[] out = new char[encoded.length];
    for (int i = 0; i < encoded.length; i++) {
      out[i] = (char) encoded[i];
    }
    Arrays.fill(encoded, (byte) 0);
    return out;
  }

  private static byte[] decode(char[] chars) {
    byte[] ascii = new byte[chars.length];
    for (int i = 0; i < chars.length; i++) {
      ascii[i] = (byte) chars[i];
    }
    try {
      return Base64.getDecoder().decode(ascii);
    } finally {
      Arrays.fill(ascii, (byte) 0);
    }
  }
}
