package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import ai.msrv.gaterook.plugin.protocol.SecretWire;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The secrets side of the host protocol in the core (report 16 §5): a 128-bit {@code callToken} per call, bound to the
 * host instance, generation, connection and call. A host asks {@code secrets/get|login|meta|sshCertificate} with the
 * token; the registry hands out only the connection's own {@code writeOnly} fields, only while the call runs, at most
 * {@value #MAX_READS} times. A token that is stale, of another host, or names a field the connection doesn't have is
 * refused with {@code -32020}. Values go out written straight from their {@code char[]} ({@link SecretWire}); every
 * value handed out is tainted, so the result and the host's log mask any echo.
 *
 * <p>The registry holds no secret store and no master key: it only lends out the {@link SecretResolver} the core
 * already scoped to the connection and call.
 */
public final class SecretCallbackRegistry {

  /** Secret reads one call may make (report 16 §5). */
  static final int MAX_READS = 16;
  /**
   * Values one call may add to its taint ({@code taint/add}), and how long one may be: a credential the plugin
   * derived is short, and every value costs the masker a dozen forms (review 2026-10-27: unbounded, a plugin could
   * make the core hold gigabytes).
   */
  static final int MAX_TAINTS = 64;
  static final int MAX_TAINT_CHARS = 8 * 1024;

  private static final Logger log = LoggerFactory.getLogger(SecretCallbackRegistry.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** One live call's secret access. */
  private static final class Scope {
    final long hostInstance;
    final String generation;
    final String connectionId;
    final String callId;
    final Set<String> fields;
    final SecretResolver resolver;
    final ExecContext ctx;
    final PluginLog hostLog;
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger taints = new AtomicInteger();
    final java.util.concurrent.atomic.AtomicBoolean warned = new java.util.concurrent.atomic.AtomicBoolean();

    Scope(long hostInstance, String generation, String connectionId, String callId, Set<String> fields,
        SecretResolver resolver, ExecContext ctx, PluginLog hostLog) {
      this.hostInstance = hostInstance;
      this.generation = generation;
      this.connectionId = connectionId;
      this.callId = callId;
      this.fields = fields;
      this.resolver = resolver;
      this.ctx = ctx;
      this.hostLog = hostLog;
    }
  }

  private final Map<String, Scope> byToken = new ConcurrentHashMap<>();

  /** A token for one call; {@code close} the returned handle when the call ends. */
  public Permit open(long hostInstance, String generation, String connectionId, String callId, Set<String> fields,
      SecretResolver resolver, ExecContext ctx, PluginLog hostLog) {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    String token = "ct_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Scope scope = new Scope(hostInstance, generation, connectionId, callId, fields, resolver, ctx, hostLog);
    byToken.put(token, scope);
    return new Permit(token, scope);
  }

  /** The live token of a call; closing it stops the host asking for the call's secrets. */
  public final class Permit implements AutoCloseable {
    private final String token;
    private final Scope scope;

    Permit(String token, Scope scope) {
      this.token = token;
      this.scope = scope;
    }

    public String token() {
      return token;
    }

    /**
     * Whether the host sent more {@code taint/add} than a call may (or a value too long to mask): a value it meant to
     * mask may not be, so the call's answer must not reach anyone.
     */
    public boolean taintOverLimit() {
      return scope.warned.get() || scope.taints.get() > MAX_TAINTS;
    }

    @Override
    public void close() {
      byToken.remove(token);
    }
  }

  // ── Host → core ─────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code secrets/get}. */
  JsonRpcPeer.RawResult get(long hostInstance, JsonNode params) throws RpcException {
    Scope scope = scope(hostInstance, params);
    String field = field(params);
    countRead(scope);
    try {
      SecretResolver.Secret secret = scope.resolver.secret(field);
      taint(scope, secret);
      return SecretWire.text(secret.chars().clone(), secret.version(), secret.notAfter().orElse(null));
    } catch (SecretUnavailableException e) {
      throw new RpcException(Gpp.SECRET_UNAVAILABLE, e.getMessage());
    }
  }

  /** {@code secrets/login}. */
  JsonRpcPeer.RawResult login(long hostInstance, JsonNode params) throws RpcException {
    Scope scope = scope(hostInstance, params);
    String field = field(params);
    countRead(scope);
    try {
      SecretResolver.Login login = scope.resolver.login(field);
      if (scope.hostLog != null) {
        scope.hostLog.taint(charSeq(login.password()));
      }
      return SecretWire.login(login.username(), login.password().chars().clone(), login.password().version(),
          login.password().notAfter().orElse(null));
    } catch (SecretUnavailableException e) {
      throw new RpcException(Gpp.SECRET_UNAVAILABLE, e.getMessage());
    }
  }

  /** {@code secrets/meta}: no value, only pool keys and UI hints. */
  JsonNode meta(long hostInstance, JsonNode params) throws RpcException {
    Scope scope = scope(hostInstance, params);
    String field = field(params);
    SecretResolver.Meta meta = scope.resolver.meta(field);
    ObjectNode o = GppJson.object();
    o.put("set", scope.resolver.isSet(field));
    o.put("source", meta.source());
    o.put("version", meta.version());
    o.put("notAfter", meta.notAfter() == null ? null : meta.notAfter().toString());
    return o;
  }

  /** {@code secrets/sshCertificate}: the connection's Vault signs the plugin's key. */
  JsonNode sshCertificate(long hostInstance, JsonNode params) throws RpcException {
    Scope scope = scope(hostInstance, params);
    String field = field(params);
    try {
      byte[] spki = Base64.getDecoder().decode(params.path("publicKey").asString(""));
      String algorithm = params.path("algorithm").asString("Ed25519");
      java.security.PublicKey key = java.security.KeyFactory.getInstance(algorithm)
          .generatePublic(new java.security.spec.X509EncodedKeySpec(spki));
      Map<String, String> extensions = new java.util.LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> e : params.path("extensions").properties()) {
        extensions.put(e.getKey(), e.getValue().asString());
      }
      SecretResolver.SshCertRequest request = new SecretResolver.SshCertRequest(params.path("principal").asString(null),
          java.time.Duration.ofSeconds(params.path("ttlSeconds").asLong(0)), params.path("keyId").asString(null),
          extensions);
      countRead(scope);
      SecretResolver.SshCertificate cert = scope.resolver.sshCertificate(field, key, request);
      ObjectNode o = GppJson.object();
      o.put("certificate", cert.openSshText());
      o.put("serial", cert.serial());
      o.put("notAfter", cert.notAfter() == null ? null : cert.notAfter().toString());
      return o;
    } catch (SecretUnavailableException e) {
      throw new RpcException(Gpp.SECRET_UNAVAILABLE, e.getMessage());
    } catch (java.security.GeneralSecurityException | RuntimeException e) {
      throw new RpcException(Gpp.INVALID_PARAMS, "The public key or request isn't valid: " + e.getMessage());
    }
  }

  /** {@code taint/add}: a credential the plugin derived itself, masked like a resolver value. */
  void taintAdd(long hostInstance, JsonNode params) {
    Scope scope = byToken.get(params.path("callToken").asString());
    if (scope == null || scope.hostInstance != hostInstance) {
      return;
    }
    String value = params.path("value").asString(null);
    if (value == null || value.length() > MAX_TAINT_CHARS || scope.taints.incrementAndGet() > MAX_TAINTS) {
      if (value != null && scope.warned.compareAndSet(false, true)) {
        log.warn("Plugin {} sent a taint/add over its limits ({} values of up to {} characters a call); ignored",
            scope.generation, MAX_TAINTS, MAX_TAINT_CHARS);
      }
      return;
    }
    scope.ctx.taint(value);
    if (scope.hostLog != null) {
      scope.hostLog.taint(value);
    }
  }

  private Scope scope(long hostInstance, JsonNode params) throws RpcException {
    String token = params.path("callToken").asString(null);
    Scope scope = token == null ? null : byToken.get(token);
    if (scope == null) {
      throw new RpcException(Gpp.SECRET_DENIED, "No secret for this token: the call is over.");
    }
    if (scope.hostInstance != hostInstance) {
      log.warn("A host asked for a secret with a token of another host (generation {})", scope.generation);
      throw new RpcException(Gpp.SECRET_DENIED, "This token isn't this host's.");
    }
    return scope;
  }

  private String field(JsonNode params) throws RpcException {
    String field = params.path("field").asString(null);
    Scope scope = byToken.get(params.path("callToken").asString());
    if (field == null || scope == null || !scope.fields.contains(field)) {
      throw new RpcException(Gpp.SECRET_DENIED, "The connection has no secret field " + field + ".");
    }
    return field;
  }

  /** Counts one value read against the call's budget; the field was already checked to be the connection's. */
  private static void countRead(Scope scope) throws RpcException {
    if (scope.reads.incrementAndGet() > MAX_READS) {
      throw new RpcException(Gpp.SECRET_DENIED, "A call may read at most " + MAX_READS + " secrets.");
    }
  }

  private static void taint(Scope scope, SecretResolver.Secret secret) {
    // The scoped resolver already tainted the call for result masking; the host's log needs the value too.
    if (scope.hostLog != null && secret.chars().length > 0) {
      scope.hostLog.taint(charSeq(secret));
    }
  }

  private static CharSequence charSeq(SecretResolver.Secret secret) {
    return java.nio.CharBuffer.wrap(secret.chars());
  }

  /** Whether {@code token} is a live call of {@code hostInstance} (handles are sealed only for a live call). */
  boolean liveFor(long hostInstance, String token) {
    return callOf(hostInstance, token).isPresent();
  }

  /** The plugin and the connection of a live call. */
  record LiveCall(String pluginId, String connectionId) {}

  /** The plugin (its generation's id) and connection a live call of {@code hostInstance} runs for, or empty. */
  java.util.Optional<LiveCall> callOf(long hostInstance, String token) {
    Scope scope = token == null ? null : byToken.get(token);
    if (scope == null || scope.hostInstance != hostInstance) {
      return java.util.Optional.empty();
    }
    int at = scope.generation.indexOf('@');
    return java.util.Optional.of(new LiveCall(at < 0 ? scope.generation : scope.generation.substring(0, at),
        scope.connectionId));
  }

  /** How many calls still hold a token (tests). */
  int liveTokens() {
    return byToken.size();
  }
}
