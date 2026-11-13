package com.gaterook.core.connectors.plugins;

import com.gaterook.core.platform.SealedHandles;
import com.gaterook.plugin.protocol.Gpp;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.JsonRpcPeer;
import com.gaterook.plugin.protocol.RpcException;
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
      case Gpp.QUOTA_ACQUIRE -> quota(params);
      case Gpp.MASK_PREVIEW -> masked(params);
      default -> throw new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + method);
    };
  }

  @Override
  public void notification(String method, JsonNode params) {
    switch (method) {
      case Gpp.LOG -> log.line(params.path("logger").asString(null), params.path("level").asString("info"),
          params.path("message").asString(""));
      case Gpp.TAINT_ADD -> secrets.taintAdd(hostInstance, params);
      case Gpp.PROGRESS -> services(params).ifPresent(ctx -> ctx.progress(params.path("progress").asDouble(0),
          params.path("total").isNumber() ? Double.valueOf(params.get("total").asDouble()) : null,
          clip(params.path("message").asString(null), MAX_MESSAGE_CHARS)));
      case Gpp.CLIENT_LOG -> services(params).ifPresent(ctx -> {
        JsonNode data = params.path("data");
        if (com.gaterook.plugin.protocol.Canonical.bytes(data).length <= MAX_PAYLOAD_BYTES) {
          ctx.clientLog(params.path("level").asString("info"), clip(params.path("logger").asString(null), 64), data);
        }
      });
      default -> {
        // connector/toolsChanged isn't used by static plugins; ignore anything else.
      }
    }
  }

  /** The longest progress message a module's call passes on. */
  static final int MAX_MESSAGE_CHARS = 500;

  /**
   * The context of a live call of a connector module. A plugin's call has none: a plugin can't draw on the quotas
   * that GateRook's own connectors share, nor ask what the core masks.
   */
  private Optional<com.gaterook.connector.api.ExecContext> services(JsonNode params) {
    return secrets.services(hostInstance, params.path("callToken").asString(null));
  }

  /** {@code quota/acquire}: a permit of a quota shared by every connector that reaches the same account. */
  private JsonNode quota(JsonNode params) throws RpcException {
    com.gaterook.connector.api.ExecContext ctx = services(params).orElseThrow(() ->
        new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + Gpp.QUOTA_ACQUIRE));
    String key = params.path("key").asString("");
    int permits = params.path("permitsPerMinute").asInt(0);
    if (key.isBlank() || key.length() > 200 || permits < 1) {
      throw new RpcException(Gpp.INVALID_PARAMS, "A quota has a key and at least one permit a minute.");
    }
    return GppJson.object().put("granted", ctx.acquire(key, permits));
  }

  /** {@code mask/preview}: a tree as the core will mask the call's answer. */
  private JsonNode masked(JsonNode params) throws RpcException {
    com.gaterook.connector.api.ExecContext ctx = services(params).orElseThrow(() ->
        new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + Gpp.MASK_PREVIEW));
    JsonNode tree = params.path("tree");
    ObjectNode o = GppJson.object();
    o.set("tree", tree.isMissingNode() ? GppJson.MAPPER.nullNode() : ctx.masked(tree));
    return o;
  }

  private static String clip(String text, int max) {
    return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  private JsonNode seal(JsonNode params) throws RpcException {
    SecretCallbackRegistry.LiveCall call = requireLive(params);
    String purpose = purpose(params);
    JsonNode payload = params.path("payload").isMissingNode() ? GppJson.MAPPER.nullNode() : params.get("payload");
    if (com.gaterook.plugin.protocol.Canonical.bytes(payload).length > MAX_PAYLOAD_BYTES) {
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
