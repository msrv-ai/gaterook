package ai.msrv.gaterook.core.mcpservers.internal;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.core.platform.Taint;
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

/**
 * The secrets of a server that isn't saved yet (Review and add): the values the person typed for its variables or
 * headers, for this one start; references to an external store go to {@code fallback}. Every value is tainted, every
 * copy zeroed on {@link #close()}; nothing here is persisted.
 */
public final class TypedSecrets implements SecretResolver, AutoCloseable {

  private final Map<String, char[]> values = new HashMap<>();
  private final SecretResolver fallback;
  private final Taint taint;
  private final List<char[]> handedOut = new ArrayList<>();

  /** @param typed values by field, copied here (the caller zeroes its own) */
  public TypedSecrets(Map<String, char[]> typed, SecretResolver fallback, Taint taint) {
    typed.forEach((field, value) -> values.put(field, value.clone()));
    this.fallback = fallback;
    this.taint = taint;
  }

  @Override
  public Secret secret(String field) throws SecretUnavailableException {
    char[] value = values.get(field);
    if (value == null) {
      if (fallback != null) {
        return fallback.secret(field);
      }
      throw new SecretUnavailableException(field + " has no value: enter it before you start the server", null);
    }
    char[] copy = value.clone();
    taint.add(CharBuffer.wrap(copy));
    synchronized (handedOut) {
      handedOut.add(copy);
    }
    return new Secret() {
      @Override
      public char[] chars() {
        return copy;
      }

      @Override
      public byte[] bytes() {
        return new String(copy).getBytes(StandardCharsets.UTF_8);
      }

      @Override
      public String unsafeString() {
        return new String(copy);
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
        Arrays.fill(copy, '\0');
      }

      @Override
      public String toString() {
        return "‹secret›";
      }
    };
  }

  @Override
  public Login login(String field) throws SecretUnavailableException {
    throw new SecretUnavailableException("An MCP server's variables and headers are single values, not logins", null);
  }

  @Override
  public boolean isSet(String field) {
    return values.containsKey(field) || (fallback != null && fallback.isSet(field));
  }

  @Override
  public Meta meta(String field) {
    if (values.containsKey(field)) {
      return new Meta("local", "draft", null);
    }
    return fallback == null ? new Meta(null, null, null) : fallback.meta(field);
  }

  @Override
  public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
      throws SecretUnavailableException {
    throw new SecretUnavailableException("MCP servers take no SSH certificates", null);
  }

  @Override
  public void close() {
    synchronized (handedOut) {
      handedOut.forEach(v -> Arrays.fill(v, '\0'));
      handedOut.clear();
    }
    values.values().forEach(v -> Arrays.fill(v, '\0'));
    values.clear();
    if (fallback instanceof AutoCloseable c) {
      try {
        c.close();
      } catch (Exception ignored) {
        // The store's resolver zeroes its own values.
      }
    }
  }

  @Override
  public String toString() {
    return "TypedSecrets[" + values.size() + " field(s)]";
  }
}
