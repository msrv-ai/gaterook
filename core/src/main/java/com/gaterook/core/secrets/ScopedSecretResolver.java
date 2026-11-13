package com.gaterook.core.secrets;

import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.SecretUnavailableException;
import com.gaterook.core.platform.SecretFingerprints;
import com.gaterook.core.platform.Taint;
import com.gaterook.core.secrets.internal.persistence.SecretKind;
import com.gaterook.core.secrets.store.LeaseManager;
import com.gaterook.core.secrets.store.StoreResolution;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;

/**
 * The {@link SecretResolver} of one owner and one call (ARCHITECTURE.md §5 step 10, §9): only the secret fields of
 * this connection ({@code ConnectionConfig.secretFields}), so a connector — or a plugin through GPP/1 — never gets
 * another connection's secret. Every value handed out goes into the call's {@link Taint}; the masker removes any echo
 * of it. {@link #close()} zeroes everything handed out; the core closes the resolver when the call ends.
 *
 * <p>A field may be stored here, be a reference into an external store (read at call time, with the store's tokens
 * tainted too), or Vault dynamic credentials ({@code meta(field).source() == "dynamic"}: read with {@link #login}).
 * {@link #sshCertificate} has the Vault named in the connection's config sign a key for this call.
 */
public final class ScopedSecretResolver implements SecretResolver, AutoCloseable {

  /**
   * What a resolver knows beyond its owner and fields.
   *
   * @param versions fields read at another version (test before Replace)
   * @param drafts   references typed into a Test connection form, not stored
   * @param config   the connection's non-secret config ({@link #sshCertificate}), or {@code null}
   * @param env      the connection's environment (cache limits of prod), or {@code null}
   */
  public record Scope(Map<String, Integer> versions, Map<String, SecretService.DraftReference> drafts, JsonNode config,
      Env env) {
    static final Scope NONE = new Scope(Map.of(), Map.of(), null, null);
  }

  private static final JsonFactory JSON = JsonFactory.builder().build();

  private final SecretService service;
  private final SecretOwner owner;
  private final Set<String> fields;
  private final Taint taint;
  private final Scope scope;
  private final List<AutoCloseable> handedOut = new ArrayList<>();
  private final List<StoreResolution.DraftLease> draftLeases = new ArrayList<>();
  private volatile boolean closed;

  ScopedSecretResolver(SecretService service, SecretOwner owner, Set<String> fields, Taint taint, Scope scope) {
    this.service = service;
    this.owner = owner;
    this.fields = Set.copyOf(fields);
    this.taint = taint;
    this.scope = scope;
  }

  @Override
  public Secret secret(String field) throws SecretUnavailableException {
    SecretService.Resolved resolved = resolve(field);
    LocalSecretValue value = new LocalSecretValue(resolved.plaintext(), resolved.version());
    if (value.chars().length > 0) {
      taint.add(CharBuffer.wrap(value.chars()));
      SecretFingerprints.process().learnValue(CharBuffer.wrap(value.chars()));
    } else {
      // Not UTF-8 (a keystore, a keytab): no text view, but its encodings can still be echoed.
      taint.addBytes(value.bytes());
      SecretFingerprints.process().learnBytes(value.bytes());
    }
    remember(value);
    return value;
  }

  /**
   * A login: Vault dynamic credentials (a lease shared by this connection's calls); the password of {@code field} with
   * the user its login reads from a store ({@code /credentials/user} next to {@code /credentials/password}); or a value
   * stored as {@code {"username": …, "password": …}} — here or in a store. The username is not secret, the password is
   * (and both the password and {@code Base64(user:password)} go into the taint).
   */
  @Override
  public Login login(String field) throws SecretUnavailableException {
    check(field);
    SecretService.DraftReference draft = scope.drafts().get(field);
    if (draft != null && draft.dynamic()) {
      StoreResolution.DraftLease lease = service.draftLogin(field, draft, taint);
      synchronized (draftLeases) {
        draftLeases.add(lease);
      }
      LocalSecretValue password = new LocalSecretValue(lease.password().clone(), "draft");
      Login login = new Login(lease.username(), password, "draft", lease.notAfter());
      remember(login);
      return login;
    }
    if (draft == null && service.describeField(owner, field).kind() == SecretKind.DYNAMIC) {
      LeaseManager.Login lease = service.dynamicLogin(owner, field, taint);
      LocalSecretValue password = new LocalSecretValue(lease.password(), lease.version());
      Login login = new Login(lease.username(), password, lease.version(), lease.notAfter());
      remember(login);
      return login;
    }
    String userField = userFieldOf(field);
    if (userField != null) {
      return storedUserLogin(field, userField);
    }
    SecretService.Resolved resolved = resolve(field);
    byte[] json = resolved.plaintext();
    String username = null;
    char[] password = null;
    try (JsonParser parser = JSON.createParser(ObjectReadContext.empty(), json)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        throw notALogin(resolved);
      }
      while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
        String name = parser.currentName();
        JsonToken token = parser.nextToken();
        if ("username".equals(name) && token == JsonToken.VALUE_STRING) {
          username = parser.getString();
        } else if ("password".equals(name) && token == JsonToken.VALUE_STRING) {
          // Straight from the parser's buffer into our own char[]: no String of the password.
          password = Arrays.copyOfRange(parser.getStringCharacters(), parser.getStringOffset(),
              parser.getStringOffset() + parser.getStringLength());
        } else {
          parser.skipChildren();
        }
      }
    } catch (RuntimeException e) {
      throw notALogin(resolved);
    } finally {
      Arrays.fill(json, (byte) 0);
    }
    if (password == null) {
      throw notALogin(resolved);
    }
    LocalSecretValue secret = new LocalSecretValue(password, resolved.version());
    taint.addLogin(username, CharBuffer.wrap(secret.chars()));
    SecretFingerprints.process().learnLogin(username, CharBuffer.wrap(secret.chars()));
    Login login = new Login(username, secret, resolved.version(), null);
    remember(login);
    return login;
  }

  /** The longest text a field read from a store may hold: a CA certificate fits, a whole kubeconfig too. */
  private static final int MAX_TEXT = 65_536;

  /**
   * The value of a text field read from a store ({@code vault://kv/prod/db#username}), for the config the core hands
   * the connector: not a secret by the field's schema, so it isn't tainted (the core's call, never a connector's).
   * Leading and trailing whitespace is dropped (a value written from a file ends with a newline).
   *
   * @throws SecretUnavailableException when the store can't answer, or holds no text such a field can take
   */
  public String text(String field) throws SecretUnavailableException {
    SecretService.Resolved resolved = resolve(field);
    byte[] bytes = resolved.plaintext();
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().strip();
    } catch (CharacterCodingException e) {
      throw new SecretUnavailableException(resolved.name() + " is not text", null);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
    if (text.isEmpty() || text.length() > MAX_TEXT
        || text.chars().anyMatch(c -> (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7f)) {
      throw new SecretUnavailableException(resolved.name() + " is not text this field can take", null);
    }
    return text;
  }

  @Override
  public boolean isSet(String field) {
    if (!fields.contains(field)) {
      return false;
    }
    if (scope.drafts().containsKey(field)) {
      return true;
    }
    SecretService.Described described = service.describeField(owner, field);
    return described.kind() != null && (described.kind() != SecretKind.LOCAL || described.version() != null);
  }

  @Override
  public Meta meta(String field) {
    if (!fields.contains(field)) {
      return new Meta(null, null, null);
    }
    SecretService.DraftReference draft = scope.drafts().get(field);
    if (draft != null) {
      return new Meta(draft.dynamic() ? "dynamic" : "reference", "draft", null);
    }
    SecretService.Described described = service.describeField(owner, field);
    if (described.kind() == null) {
      return new Meta(null, null, null);
    }
    String version = switch (described.kind()) {
      case LOCAL -> {
        Integer v = scope.versions().getOrDefault(field, described.version());
        yield v == null ? null : "v" + v;
      }
      case REFERENCE, DYNAMIC -> described.remoteVersion();
    };
    return new Meta(described.kind().name().toLowerCase(java.util.Locale.ROOT), version, described.notAfter());
  }

  /**
   * Has the Vault named in the connection's config at {@code field} ({@code {store, mount, role, ttl}}, e.g.
   * {@code /credentials/vault} of SSH) sign {@code key} for this call; the key is the connector's, made for the call.
   */
  @Override
  public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
      throws SecretUnavailableException {
    if (closed) {
      throw new SecretUnavailableException("The call ended: its secrets are gone", null);
    }
    JsonNode at = scope.config() == null || field == null ? null : scope.config().at(field);
    if (at == null || at.isMissingNode()) {
      throw new SecretUnavailableException("SSH certificates are signed by a connected Vault; " + field
          + " names none here.", null);
    }
    return service.sshCertificate(owner, at, key, request, taint);
  }

  /** Zeroes every value handed out during the call; credentials leased for a draft's test are revoked. */
  @Override
  public void close() {
    closed = true;
    synchronized (handedOut) {
      for (AutoCloseable value : handedOut) {
        try {
          value.close();
        } catch (Exception ignored) {
          // Zeroing never fails; nothing else to do.
        }
      }
      handedOut.clear();
    }
    synchronized (draftLeases) {
      draftLeases.forEach(service::revokeDraft);
      draftLeases.clear();
    }
  }

  @Override
  public String toString() {
    return "ScopedSecretResolver[" + owner.kind() + " " + owner.id() + ", " + fields.size() + " field(s)]";
  }

  private void check(String field) throws SecretUnavailableException {
    if (closed) {
      throw new SecretUnavailableException("The call ended: its secrets are gone", null);
    }
    if (!fields.contains(field)) {
      // Not a secret field of this connection: a connector can't reach other connections' secrets.
      throw new SecretUnavailableException(field + " is not a secret field of this connection", null);
    }
  }

  private SecretService.Resolved resolve(String field) throws SecretUnavailableException {
    check(field);
    SecretService.DraftReference draft = scope.drafts().get(field);
    SecretService.Resolved resolved = draft != null ? service.resolveDraft(field, draft, taint)
        : service.resolve(owner, field, scope.versions().get(field), scope.env(), taint);
    if (resolved.plaintext() == null) {
      throw new SecretUnavailableException(resolved.unavailable(), null);
    }
    return resolved;
  }

  /** The user names a login's user field may have, next to its password. */
  private static final List<String> USER_NAMES = List.of("user", "username", "login");

  /**
   * The user field of {@code field}'s login when its value is read from a store too (a field of this connection, set
   * as a reference): {@code /credentials/user} for {@code /credentials/password}; {@code null} for a typed user.
   */
  private String userFieldOf(String field) {
    int slash = field.lastIndexOf('/');
    if (slash < 0) {
      return null;
    }
    for (String name : USER_NAMES) {
      String candidate = field.substring(0, slash + 1) + name;
      if (!candidate.equals(field) && fields.contains(candidate) && isSet(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /** The password of {@code field} with the user read from {@code userField}: both from where they are kept. */
  private Login storedUserLogin(String field, String userField) throws SecretUnavailableException {
    SecretService.Resolved user = resolve(userField);
    String username;
    byte[] name = user.plaintext();
    try {
      username = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(name)).toString().strip();
    } catch (CharacterCodingException e) {
      throw new SecretUnavailableException(user.name() + " is not a user name", null);
    } finally {
      Arrays.fill(name, (byte) 0);
    }
    if (username.isEmpty() || username.length() > 256 || username.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw new SecretUnavailableException(user.name() + " is not a user name", null);
    }
    SecretService.Resolved password = resolve(field);
    LocalSecretValue secret = new LocalSecretValue(password.plaintext(), password.version());
    taint.addLogin(username, CharBuffer.wrap(secret.chars()));
    SecretFingerprints.process().learnLogin(username, CharBuffer.wrap(secret.chars()));
    String version = java.util.Objects.equals(password.version(), user.version()) ? password.version()
        : password.version() + "+" + user.version();
    Login login = new Login(username, secret, version, null);
    remember(login);
    return login;
  }

  private void remember(AutoCloseable value) {
    synchronized (handedOut) {
      handedOut.add(value);
    }
  }

  private static SecretUnavailableException notALogin(SecretService.Resolved resolved) {
    return new SecretUnavailableException(resolved.name() + " is not a login (username and password)", null);
  }
}
