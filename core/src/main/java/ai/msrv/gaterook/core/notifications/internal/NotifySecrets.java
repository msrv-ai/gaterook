package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretOwners;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The channels' tokens in the {@code secrets} module (owner kind {@code notification}, id = the channel): Slack's bot
 * ({@code xoxb-}) and app-level ({@code xapp-}) tokens, Telegram's bot token. Values are opened for one use and
 * closed after it; every value opened is tainted, so an echo of it in an error text is masked before it is logged or
 * shown. Also tells the Secrets screen what uses them.
 */
@Component
public class NotifySecrets implements SecretOwners {

  /** The config key and the secret field (a JSON pointer) of each token. */
  public static final Map<String, String> FIELDS = Map.of("botToken", "/botToken", "appToken", "/appToken");

  private final ObjectProvider<SecretService> secrets;

  NotifySecrets(ObjectProvider<SecretService> secrets) {
    this.secrets = secrets;
  }

  public static SecretOwner owner(NotifyChannel channel) {
    return new SecretOwner(SecretOwner.Kind.NOTIFICATION, channel.id());
  }

  /** Tokens opened for one call; {@link #close} zeroes them. {@code toString()} prints no value. */
  public static final class Tokens implements AutoCloseable {

    private final ScopedSecretResolver resolver;
    private final Map<String, SecretResolver.Secret> open = new LinkedHashMap<>();
    private final Taint taint;

    private Tokens(ScopedSecretResolver resolver, Taint taint) {
      this.resolver = resolver;
      this.taint = taint;
    }

    /** A token as the HTTP header or the URL needs it; {@code null} when none is stored. */
    public String get(String key) {
      SecretResolver.Secret s = open.get(key);
      return s == null ? null : s.unsafeString();
    }

    public boolean has(String key) {
      return open.containsKey(key);
    }

    /** Every value opened: mask with it before an error text goes anywhere. */
    public Taint taint() {
      return taint;
    }

    @Override
    public void close() {
      open.values().forEach(SecretResolver.Secret::close);
      open.clear();
      if (resolver != null) {
        resolver.close();
      }
    }

    @Override
    public String toString() {
      return "‹secret›";
    }
  }

  /**
   * Opens the tokens of {@code channel} named by {@code keys}; a token not stored is missing from the result.
   *
   * @param extra tokens typed into a form and not stored yet (Test before Save): they win over the stored ones
   */
  public Tokens open(NotifyChannel channel, Map<String, char[]> extra, String... keys) {
    Taint taint = new Taint();
    List<String> fields = new ArrayList<>();
    for (String key : keys) {
      fields.add(FIELDS.get(key));
    }
    ScopedSecretResolver resolver = secrets.getObject().resolver(owner(channel), fields, taint);
    Tokens tokens = new Tokens(resolver, taint);
    for (String key : keys) {
      char[] typed = extra == null ? null : extra.get(key);
      if (typed != null) {
        tokens.open.put(key, new Typed(typed));
        taint.add(new String(typed));
        continue;
      }
      if (resolver.isSet(FIELDS.get(key))) {
        try {
          tokens.open.put(key, resolver.secret(FIELDS.get(key)));
        } catch (SecretUnavailableException e) {
          // Stored but unreadable (locked core, destroyed version): the channel reports a missing token.
        }
      }
    }
    return tokens;
  }

  /**
   * Stores {@code value} as the token of {@code key}: a new version of the existing secret, or a new secret. Call in
   * the transaction that saves the channel.
   *
   * @return the secret's id, for the config's {@code {"$secret": id}}
   */
  public String store(NotifyChannel channel, String key, char[] value, String reason) {
    SecretService service = secrets.getObject();
    String field = FIELDS.get(key);
    Optional<SecretInfo> existing = service.listForOwner(owner(channel)).stream()
        .filter(s -> s.field().equals(field)).findFirst();
    if (existing.isPresent()) {
      service.replace(existing.get().id(), value, false, reason);
      return existing.get().id();
    }
    return service.createLocal(owner(channel), field, channel.id() + "/" + switch (key) {
      case "appToken" -> "app-token";
      default -> "bot-token";
    }, value).id();
  }

  /** What is stored for the channel: field, state and version, never a value. */
  public List<SecretInfo> stored(NotifyChannel channel) {
    return secrets.getObject().listForOwner(owner(channel));
  }

  /** Destroys every token of {@code channel} (Disconnect). Call in the disconnect's transaction. */
  public List<String> destroy(NotifyChannel channel) {
    return secrets.getObject().deleteForOwner(owner(channel));
  }

  @Override
  public SecretOwner.Kind kind() {
    return SecretOwner.Kind.NOTIFICATION;
  }

  @Override
  public Optional<Owner> describe(String ownerId) {
    return switch (ownerId) {
      case "slack" -> Optional.of(new Owner("Slack notifications", null, List.of("Settings › Notifications")));
      case "telegram" -> Optional.of(new Owner("Telegram notifications", null, List.of("Settings › Notifications")));
      default -> Optional.empty();
    };
  }

  @Override
  public Optional<String> rotateUnavailable(String ownerId, String field) {
    return Optional.of("Slack and Telegram issue their tokens themselves: generate a new one there and replace it.");
  }

  /** A token typed into a form for a test: the same view as a stored one, zeroed on close. */
  private static final class Typed implements SecretResolver.Secret {

    private final char[] chars;

    Typed(char[] chars) {
      this.chars = chars.clone();
    }

    @Override
    public char[] chars() {
      return chars;
    }

    @Override
    public byte[] bytes() {
      return new String(chars).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String unsafeString() {
      return new String(chars);
    }

    @Override
    public String version() {
      return "typed";
    }

    @Override
    public Optional<java.time.Instant> notAfter() {
      return Optional.empty();
    }

    @Override
    public void close() {
      java.util.Arrays.fill(chars, '\0');
    }

    @Override
    public String toString() {
      return "‹secret›";
    }
  }
}
