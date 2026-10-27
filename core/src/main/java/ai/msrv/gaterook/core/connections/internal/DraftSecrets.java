package ai.msrv.gaterook.core.connections.internal;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.core.platform.Taint;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * The secrets of a Test connection before anything is saved ({@code POST /api/connections/test}): values typed into
 * the form for this one request, and for an edit the stored values of the fields the form left alone. Every value
 * handed out is tainted; all copies are zeroed on {@link #close()}. Nothing here is ever persisted.
 */
public final class DraftSecrets implements SecretResolver, AutoCloseable {

  private static final JsonFactory JSON = JsonFactory.builder().build();

  private final Set<String> fields;
  private final Map<String, char[]> values = new HashMap<>();
  private final SecretResolver stored;
  private final Taint taint;
  private final List<AutoCloseable> handedOut = new ArrayList<>();
  private volatile boolean closed;

  /**
   * @param fields every secret field of the connection
   * @param typed  values from the form, copied here (the caller zeroes its own)
   * @param stored the stored secrets of the connection being edited, or {@code null}
   */
  public DraftSecrets(Set<String> fields, Map<String, char[]> typed, SecretResolver stored, Taint taint) {
    this.fields = Set.copyOf(fields);
    typed.forEach((field, value) -> values.put(field, value.clone()));
    this.stored = stored;
    this.taint = taint;
  }

  @Override
  public Secret secret(String field) throws SecretUnavailableException {
    char[] value = typed(field);
    if (value == null) {
      return storedOrFail(field).secret(field);
    }
    DraftValue secret = new DraftValue(value.clone());
    taint.add(CharBuffer.wrap(secret.chars()));
    remember(secret);
    return secret;
  }

  @Override
  public Login login(String field) throws SecretUnavailableException {
    char[] value = typed(field);
    if (value == null) {
      return storedOrFail(field).login(field);
    }
    byte[] json = utf8(value);
    String username = null;
    char[] password = null;
    try (JsonParser parser = JSON.createParser(ObjectReadContext.empty(), json)) {
      if (parser.nextToken() == JsonToken.START_OBJECT) {
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
          String name = parser.currentName();
          JsonToken token = parser.nextToken();
          if ("username".equals(name) && token == JsonToken.VALUE_STRING) {
            username = parser.getString();
          } else if ("password".equals(name) && token == JsonToken.VALUE_STRING) {
            password = Arrays.copyOfRange(parser.getStringCharacters(), parser.getStringOffset(),
                parser.getStringOffset() + parser.getStringLength());
          } else {
            parser.skipChildren();
          }
        }
      }
    } catch (RuntimeException e) {
      password = null;
    } finally {
      Arrays.fill(json, (byte) 0);
    }
    if (password == null) {
      throw new SecretUnavailableException(field + " is not a login (username and password)", null);
    }
    DraftValue secret = new DraftValue(password);
    taint.addLogin(username, CharBuffer.wrap(secret.chars()));
    Login login = new Login(username, secret, "draft", null);
    remember(login);
    return login;
  }

  @Override
  public boolean isSet(String field) {
    if (!fields.contains(field)) {
      return false;
    }
    return values.containsKey(field) || (stored != null && stored.isSet(field));
  }

  @Override
  public Meta meta(String field) {
    if (values.containsKey(field) && fields.contains(field)) {
      return new Meta("local", "draft", null);
    }
    return stored == null ? new Meta(null, null, null) : stored.meta(field);
  }

  @Override
  public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
      throws SecretUnavailableException {
    if (stored != null) {
      return stored.sshCertificate(field, key, request);
    }
    throw new SecretUnavailableException("SSH certificates are signed by a connected Vault; " + field
        + " has none here.", null);
  }

  @Override
  public void close() {
    closed = true;
    synchronized (handedOut) {
      for (AutoCloseable value : handedOut) {
        try {
          value.close();
        } catch (Exception ignored) {
          // Zeroing never fails.
        }
      }
      handedOut.clear();
    }
    values.values().forEach(v -> Arrays.fill(v, '\0'));
    values.clear();
    if (stored instanceof AutoCloseable c) {
      try {
        c.close();
      } catch (Exception ignored) {
        // The stored resolver zeroes its own values.
      }
    }
  }

  @Override
  public String toString() {
    return "DraftSecrets[" + fields.size() + " field(s)]";
  }

  private char[] typed(String field) throws SecretUnavailableException {
    if (closed) {
      throw new SecretUnavailableException("The test ended: its secrets are gone", null);
    }
    if (!fields.contains(field)) {
      throw new SecretUnavailableException(field + " is not a secret field of this connection", null);
    }
    return values.get(field);
  }

  private SecretResolver storedOrFail(String field) throws SecretUnavailableException {
    if (stored == null) {
      throw new SecretUnavailableException(field + " is not set: enter it to test the connection", null);
    }
    return stored;
  }

  private void remember(AutoCloseable value) {
    synchronized (handedOut) {
      handedOut.add(value);
    }
  }

  private static byte[] utf8(char[] chars) {
    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
    byte[] out = new byte[encoded.remaining()];
    encoded.get(out);
    if (encoded.hasArray()) {
      Arrays.fill(encoded.array(), (byte) 0);
    }
    return out;
  }

  /** One value of a draft: a live view zeroed on close, never printed or serialized. */
  @JsonSerialize(using = Refuse.class)
  static final class DraftValue implements Secret {
    private final char[] chars;
    private final byte[] bytes;
    private volatile boolean done;

    DraftValue(char[] chars) {
      this.chars = chars;
      this.bytes = utf8(chars);
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
      return "draft";
    }

    @Override
    public Optional<Instant> notAfter() {
      return Optional.empty();
    }

    @Override
    public void close() {
      done = true;
      Arrays.fill(chars, '\0');
      Arrays.fill(bytes, (byte) 0);
    }

    @Override
    public String toString() {
      return "‹secret›";
    }

    private void check() {
      if (done) {
        throw new IllegalStateException("This secret was closed when its test ended");
      }
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
