package ai.msrv.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.core.platform.HmacKeys;
import ai.msrv.gaterook.core.platform.SealedHandles;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.MemoryPipe;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import ai.msrv.gaterook.plugin.protocol.SecretWire;
import ai.msrv.gaterook.plugin.protocol.host.WireSecret;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The core's side of the secret callback (report 16 §5): a host asks {@code secrets/get} with a call token over GPP/1,
 * and the registry hands out only the connection's own field, only while the call runs, at most sixteen times. A
 * foreign field, a seventeenth read, a token of another host and a token after the call ends are all {@code -32020}.
 * Driven through two real peers over in-memory pipes, so the token travels the wire as it would to a real host.
 */
class SecretCallbackRegistryTest {

  @TempDir
  Path tmp;

  private JsonRpcPeer core;
  private JsonRpcPeer host;
  private PluginLog log;
  private SealedHandles handles;

  @AfterEach
  void stop() {
    if (core != null) {
      core.close();
    }
    if (host != null) {
      host.close();
    }
    if (log != null) {
      log.close();
    }
  }

  @Test
  void handsOutTheConnectionsFieldOncePerCallAndRefusesTheRest() throws Exception {
    SecretCallbackRegistry registry = new SecretCallbackRegistry();
    FakeSecrets secrets = new FakeSecrets();
    start(registry);
    SecretCallbackRegistry.Permit permit = registry.open(1, "jira@1.3.0", "c1", "tc_1", Set.of("/apiToken"),
        secrets, new FakeContext(), log);

    WireSecret secret = get(permit.token(), "/apiToken");
    assertThat(new String(secret.chars())).isEqualTo("ATATT-cnry-token");
    assertThat(secrets.closed).as("the core keeps the resolver; it closes it when the call ends").isFalse();

    // A field the connection doesn't have.
    assertThatThrownBy(() -> get(permit.token(), "/otherConnectionsSecret"))
        .isInstanceOfSatisfying(RpcException.class, e -> assertThat(e.code()).isEqualTo(Gpp.SECRET_DENIED));

    // A token of this call, asked by another host instance: refused before any value is built.
    assertThatThrownBy(() -> registry.get(2, Messages.secretField(permit.token(), "/apiToken")))
        .isInstanceOfSatisfying(RpcException.class, e -> assertThat(e.code()).isEqualTo(Gpp.SECRET_DENIED));

    // At most sixteen reads per call (one already made above).
    for (int i = 0; i < 15; i++) {
      get(permit.token(), "/apiToken");
    }
    assertThatThrownBy(() -> get(permit.token(), "/apiToken"))
        .isInstanceOfSatisfying(RpcException.class, e -> assertThat(e.code()).isEqualTo(Gpp.SECRET_DENIED));

    // After the call ends the token is gone.
    permit.close();
    assertThat(registry.liveTokens()).isZero();
    assertThatThrownBy(() -> get(newCall(registry, secrets), "/apiToken"))
        .isInstanceOf(RpcException.class);
  }

  /**
   * A plugin's sealed handles are its own (review 2026-10-27): the core's HMAC key also seals the built-in connectors'
   * cursors, and a plugin that named the purpose itself could mint a {@code redis.get} cursor a built-in tool takes
   * as its own, or open one an agent hands it. Now a plugin's handle opens only for the same plugin, connection and
   * purpose, and never as a built-in's.
   */
  @Test
  void aPluginsHandlesAreItsOwnAndNeverABuiltInConnectorsCursor() throws Exception {
    SecretCallbackRegistry registry = new SecretCallbackRegistry();
    start(registry);
    FakeSecrets secrets = new FakeSecrets();
    SecretCallbackRegistry.Permit c1 = registry.open(1, "jira@1.3.0", "c1", "tc_1", Set.of("/apiToken"), secrets,
        new FakeContext(), log);
    SecretCallbackRegistry.Permit c2 = registry.open(1, "jira@1.3.0", "c2", "tc_2", Set.of("/apiToken"), secrets,
        new FakeContext(), log);

    String own = seal(c1.token(), "cursor", "{\"page\":2}");
    assertThat(open(c1.token(), "cursor", own).path("page").asInt()).isEqualTo(2);
    assertThat(open(c1.token(), "other", own).isNull()).as("another purpose").isTrue();
    assertThat(open(c2.token(), "cursor", own).isNull()).as("another connection's call").isTrue();

    // What a plugin seals as "redis.get" is no cursor of the built-in Redis connector.
    String forged = seal(c1.token(), "redis.get", "{\"v\":1,\"c\":\"c1\",\"k\":\"0\",\"o\":\"0\"}");
    assertThat(handles.open("redis.get", forged)).isEmpty();
    // And a built-in connector's cursor, handed to the plugin by an agent, doesn't open for it.
    String builtIn = handles.seal("redis.get", ai.msrv.gaterook.plugin.protocol.GppJson.MAPPER.readTree(
        "{\"v\":1,\"c\":\"c1\"}"), Duration.ofMinutes(5));
    assertThat(open(c1.token(), "redis.get", builtIn).isNull()).isTrue();

    c1.close();
    assertThatThrownBy(() -> seal(c1.token(), "cursor", "{}"))
        .isInstanceOfSatisfying(RpcException.class, e -> assertThat(e.code()).isEqualTo(Gpp.SECRET_DENIED));
    c2.close();
  }

  /**
   * {@code taint/add} is bounded (review 2026-10-27): every value costs the core a dozen masking forms, so a plugin
   * sending them without end could exhaust the core. Past the bound the values are dropped, and the call's answer is
   * refused, since a value the plugin meant to mask may not be.
   */
  @Test
  void taintAddIsBoundedAndPastTheBoundTheCallIsSpoilt() throws Exception {
    SecretCallbackRegistry registry = new SecretCallbackRegistry();
    start(registry);
    FakeContext ctx = new FakeContext();
    SecretCallbackRegistry.Permit permit = registry.open(1, "jira@1.3.0", "c1", "tc_1", Set.of("/apiToken"),
        new FakeSecrets(), ctx, log);
    for (int i = 0; i < SecretCallbackRegistry.MAX_TAINTS; i++) {
      host.notify(Gpp.TAINT_ADD, Messages.taint(permit.token(), "derived-token-" + i));
    }
    syncWith(permit);
    assertThat(ctx.tainted).hasSize(SecretCallbackRegistry.MAX_TAINTS);
    assertThat(permit.taintOverLimit()).isFalse();

    host.notify(Gpp.TAINT_ADD, Messages.taint(permit.token(), "one-too-many"));
    syncWith(permit);
    assertThat(ctx.tainted).hasSize(SecretCallbackRegistry.MAX_TAINTS).doesNotContain("one-too-many");
    assertThat(permit.taintOverLimit()).isTrue();

    SecretCallbackRegistry.Permit other = registry.open(1, "jira@1.3.0", "c1", "tc_2", Set.of("/apiToken"),
        new FakeSecrets(), new FakeContext(), log);
    host.notify(Gpp.TAINT_ADD, Messages.taint(other.token(), "x".repeat(SecretCallbackRegistry.MAX_TAINT_CHARS + 1)));
    syncWith(other);
    assertThat(other.taintOverLimit()).as("a value too long to mask").isTrue();
    permit.close();
    other.close();
  }

  /** Notifications are handled in order before a later answer: one request makes the core catch up. */
  private void syncWith(SecretCallbackRegistry.Permit permit) throws Exception {
    host.request(Gpp.SECRETS_META, Messages.secretField(permit.token(), "/apiToken")).get(Duration.ofSeconds(2));
  }

  private String seal(String token, String purpose, String payload) throws Exception {
    tools.jackson.databind.node.ObjectNode p = ai.msrv.gaterook.plugin.protocol.GppJson.object();
    p.put("callToken", token).put("purpose", purpose).put("ttlSeconds", 300);
    p.set("payload", ai.msrv.gaterook.plugin.protocol.GppJson.MAPPER.readTree(payload));
    return host.request(Gpp.HANDLES_SEAL, p).get(Duration.ofSeconds(2)).path("handle").asString();
  }

  private tools.jackson.databind.JsonNode open(String token, String purpose, String handle) throws Exception {
    tools.jackson.databind.node.ObjectNode p = ai.msrv.gaterook.plugin.protocol.GppJson.object();
    p.put("callToken", token).put("purpose", purpose).put("handle", handle);
    return host.request(Gpp.HANDLES_OPEN, p).get(Duration.ofSeconds(2)).path("payload");
  }

  private String newCall(SecretCallbackRegistry registry, FakeSecrets secrets) {
    // A token for a call that is immediately closed: asking with it must be denied.
    SecretCallbackRegistry.Permit p = registry.open(1, "jira@1.3.0", "c1", "tc_2", Set.of("/apiToken"), secrets,
        new FakeContext(), log);
    p.close();
    return p.token();
  }

  private void start(SecretCallbackRegistry registry) {
    MemoryPipe coreToHost = new MemoryPipe();
    MemoryPipe hostToCore = new MemoryPipe();
    log = new PluginLog(tmp.resolve("plugin.log"), Clock.systemUTC());
    handles = new SealedHandles(new FixedKeys(), ai.msrv.gaterook.plugin.protocol.GppJson.MAPPER, Clock.systemUTC());
    core = new JsonRpcPeer(coreToHost.in(), hostToCore.out(), new HostInbound(1, registry, handles, log),
        JsonRpcPeer.Options.defaults().name("core")).start();
    host = new JsonRpcPeer(hostToCore.in(), coreToHost.out(), (method, params, call) -> {
      throw new RpcException(Gpp.METHOD_NOT_FOUND, "the host side answers nothing here");
    }, JsonRpcPeer.Options.defaults().name("host").hostIds(true)).start();
  }

  private WireSecret get(String token, String field) throws Exception {
    return host.request(Gpp.SECRETS_GET, Messages.secretField(token, field), SecretWire.secretDecoder())
        .get(Duration.ofSeconds(2));
  }

  /** The connection's one secret field, as the core's scoped resolver would hand it out. */
  private static final class FakeSecrets implements SecretResolver {
    volatile boolean closed;

    @Override
    public Secret secret(String field) throws SecretUnavailableException {
      if (!"/apiToken".equals(field)) {
        throw new SecretUnavailableException(field + " is not a secret field of this connection", null);
      }
      char[] value = "ATATT-cnry-token".toCharArray();
      return new Secret() {
        @Override
        public char[] chars() {
          return value;
        }

        @Override
        public byte[] bytes() {
          return "ATATT-cnry-token".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String unsafeString() {
          return new String(value);
        }

        @Override
        public String version() {
          return "v3";
        }

        @Override
        public Optional<Instant> notAfter() {
          return Optional.empty();
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public Login login(String field) throws SecretUnavailableException {
      throw new SecretUnavailableException("no login", null);
    }

    @Override
    public boolean isSet(String field) {
      return "/apiToken".equals(field);
    }

    @Override
    public Meta meta(String field) {
      return new Meta("local", "v3", null);
    }

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
        throws SecretUnavailableException {
      throw new SecretUnavailableException("no Vault", null);
    }
  }

  /** The call's context; only {@code taint} is reached by the registry (for taint/add, not exercised here). */
  private static final class FakeContext implements ai.msrv.gaterook.connector.api.ExecContext {
    final CopyOnWriteArrayList<String> tainted = new CopyOnWriteArrayList<>();

    @Override public String callId() { return "tc_1"; }
    @Override public String traceId() { return null; }
    @Override public String traceparent() { return null; }
    @Override public ai.msrv.gaterook.connector.api.Env env() { return ai.msrv.gaterook.connector.api.Env.DEV; }
    @Override public ai.msrv.gaterook.connector.api.Limits limits() { return null; }
    @Override public Duration timeout() { return Duration.ofSeconds(10); }
    @Override public Instant deadline() { return Instant.now().plusSeconds(10); }
    @Override public boolean cancelled() { return false; }
    @Override public void onCancel(Runnable action) {}
    @Override public boolean approved() { return false; }
    @Override public Optional<String> approvalId() { return Optional.empty(); }
    @Override public Optional<tools.jackson.databind.JsonNode> preparedState() { return Optional.empty(); }
    @Override public Optional<tools.jackson.databind.JsonNode> approvedPayload() { return Optional.empty(); }
    @Override public System.Logger log() { return System.getLogger("t"); }
    @Override public Clock clock() { return Clock.systemUTC(); }
    @Override public HandleCodec handles() { throw new UnsupportedOperationException(); }
    @Override public boolean acquire(String remoteQuotaKey, int permitsPerMinute) { return true; }
    @Override public Path workDir() { return Path.of("target"); }
    @Override public Egress egress() { return (r, m) -> { throw new UnsupportedOperationException(); }; }
    @Override public void taint(CharSequence value) { tainted.add(value.toString()); }
  }

  /** A deterministic HmacKeys so SealedHandles can be built without the master key. */
  private static final class FixedKeys implements HmacKeys {
    @Override
    public byte[] mac(Purpose purpose, byte[] data) {
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(("test-" + purpose.name()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
