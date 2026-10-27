package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.core.platform.SealedHandles;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the core answers one host (report 16 §4.3): the host's {@code secrets/*} and {@code handles/*} requests and its
 * {@code log} and {@code taint/add} notifications, bound to this host's instance so a token can't be used across
 * hosts. Secrets go through the {@link SecretCallbackRegistry}; handles through the core's {@link SealedHandles}; log
 * lines and stderr are masked by the {@link PluginLog}.
 *
 * <p>A plugin's handles are its own (review 2026-10-27): the core's HMAC key also seals the built-in connectors'
 * cursors ({@code redis.get}, {@code k8s-list}, {@code logs.tail} …), so a plugin naming the purpose itself could mint
 * a cursor a built-in tool would take as its own, or open one an agent passed it. A plugin's handle is sealed under
 * one purpose of its own ({@value #PURPOSE}) with the plugin, the call's connection and the plugin's purpose inside,
 * and opens only for the same three.
 */
final class HostInbound implements JsonRpcPeer.Handler {

  /** The one purpose every plugin handle is sealed under; no built-in connector uses it. */
  static final String PURPOSE = "plugin";
  /** A plugin's own purpose, as a built-in connector's. */
  private static final java.util.regex.Pattern PLUGIN_PURPOSE = java.util.regex.Pattern.compile("[a-z][a-z0-9.-]{0,47}");
  /** The largest payload a plugin seals: a cursor, not storage (a handle opens only up to 16 KB). */
  static final int MAX_PAYLOAD_BYTES = 8 * 1024;

  private final long hostInstance;
  private final SecretCallbackRegistry secrets;
  private final SealedHandles handles;
  private final PluginLog log;

  HostInbound(long hostInstance, SecretCallbackRegistry secrets, SealedHandles handles, PluginLog log) {
    this.hostInstance = hostInstance;
    this.secrets = secrets;
    this.handles = handles;
    this.log = log;
  }

  @Override
  public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming call) throws RpcException {
    return switch (method) {
      case Gpp.SECRETS_GET -> secrets.get(hostInstance, params);
      case Gpp.SECRETS_LOGIN -> secrets.login(hostInstance, params);
      case Gpp.SECRETS_META -> secrets.meta(hostInstance, params);
      case Gpp.SECRETS_SSH_CERTIFICATE -> secrets.sshCertificate(hostInstance, params);
      case Gpp.HANDLES_SEAL -> seal(params);
      case Gpp.HANDLES_OPEN -> open(params);
      default -> throw new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + method);
    };
  }

  @Override
  public void notification(String method, JsonNode params) {
    switch (method) {
      case Gpp.LOG -> log.line(params.path("logger").asString(null), params.path("level").asString("info"),
          params.path("message").asString(""));
      case Gpp.TAINT_ADD -> secrets.taintAdd(hostInstance, params);
      default -> {
        // connector/toolsChanged isn't used by static plugins; ignore anything else.
      }
    }
  }

  private JsonNode seal(JsonNode params) throws RpcException {
    SecretCallbackRegistry.LiveCall call = requireLive(params);
    String purpose = purpose(params);
    JsonNode payload = params.path("payload").isMissingNode() ? GppJson.MAPPER.nullNode() : params.get("payload");
    if (ai.msrv.gaterook.plugin.protocol.Canonical.bytes(payload).length > MAX_PAYLOAD_BYTES) {
      throw new RpcException(Gpp.INVALID_PARAMS, "A handle holds at most " + MAX_PAYLOAD_BYTES + " bytes.");
    }
    ObjectNode body = GppJson.object();
    body.put("plugin", call.pluginId());
    body.put("connection", call.connectionId());
    body.put("purpose", purpose);
    body.set("payload", payload.deepCopy());
    String handle = handles.seal(PURPOSE, body, Duration.ofSeconds(params.path("ttlSeconds").asLong(0)));
    ObjectNode o = GppJson.object();
    o.put("handle", handle);
    return o;
  }

  private JsonNode open(JsonNode params) throws RpcException {
    SecretCallbackRegistry.LiveCall call = requireLive(params);
    String purpose = purpose(params);
    Optional<JsonNode> body = handles.open(PURPOSE, params.path("handle").asString(""))
        .filter(b -> call.pluginId().equals(b.path("plugin").asString(null))
            && call.connectionId().equals(b.path("connection").asString(null))
            && purpose.equals(b.path("purpose").asString(null)));
    ObjectNode o = GppJson.object();
    o.set("payload", body.map(b -> b.path("payload")).filter(p -> !p.isMissingNode())
        .orElse(GppJson.MAPPER.nullNode()));
    return o;
  }

  private SecretCallbackRegistry.LiveCall requireLive(JsonNode params) throws RpcException {
    return secrets.callOf(hostInstance, params.path("callToken").asString(null)).orElseThrow(() ->
        new RpcException(Gpp.SECRET_DENIED, "Handles are sealed only during a live call."));
  }

  private static String purpose(JsonNode params) throws RpcException {
    String purpose = params.path("purpose").asString("");
    if (!PLUGIN_PURPOSE.matcher(purpose).matches()) {
      throw new RpcException(Gpp.INVALID_PARAMS, "A handle's purpose is [a-z][a-z0-9.-]{0,47}.");
    }
    return purpose;
  }
}
