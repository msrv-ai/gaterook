package ai.msrv.gaterook.plugin.protocol;

/**
 * Names and numbers of GPP/1 (report 16 §4.3): one place for both processes, so a method renamed on one side can't
 * drift from the other.
 */
public final class Gpp {

  /** The protocol version both sides speak; {@code host/hello} refuses any other ("Plugin runtime mismatch"). */
  public static final int PROTOCOL = 1;

  /** The largest frame either side accepts (report 16 §4.2); a larger one is a protocol violation. */
  public static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

  // ── core → host ─────────────────────────────────────────────────────────────────────────────────────────────

  public static final String HOST_HELLO = "host/hello";
  public static final String HOST_PING = "host/ping";
  public static final String HOST_SHUTDOWN = "host/shutdown";
  public static final String PLUGIN_LOAD = "plugin/load";
  public static final String PLUGIN_UNLOAD = "plugin/unload";
  public static final String CONNECTOR_TOOLS = "connector/tools";
  public static final String CONNECTOR_TEST = "connector/test";
  public static final String CONNECTOR_DISCOVER_DATABASES = "connector/discoverDatabases";
  public static final String CONNECTOR_CLASSIFY = "connector/classify";
  public static final String CONNECTOR_DESCRIBE_ACTION = "connector/describeAction";
  public static final String CONNECTOR_EXECUTE = "connector/execute";
  /** Notification: the request {@code id} should stop ({@code reason}: deadline, cancelled, shutdown). */
  public static final String CANCEL = "$/cancel";
  /** Notification: a connection changed or went away; the plugin drops what it pooled for it. */
  public static final String CONNECTION_INVALIDATE = "connection/invalidate";

  // ── host → core ─────────────────────────────────────────────────────────────────────────────────────────────

  public static final String SECRETS_GET = "secrets/get";
  public static final String SECRETS_LOGIN = "secrets/login";
  public static final String SECRETS_META = "secrets/meta";
  public static final String SECRETS_SSH_CERTIFICATE = "secrets/sshCertificate";
  /** Notification: a credential the plugin derived itself (an OAuth token); masked like a resolver value. */
  public static final String TAINT_ADD = "taint/add";
  /** Sealed opaque handles (cursors): only the core holds the key, so a plugin asks it. */
  public static final String HANDLES_SEAL = "handles/seal";
  public static final String HANDLES_OPEN = "handles/open";
  /** Notification: a log line of the plugin or the host; the core masks it before it reaches a file. */
  public static final String LOG = "log";
  /** Notification: the plugin's tools changed (dynamic tools only). */
  public static final String TOOLS_CHANGED = "connector/toolsChanged";

  // ── Error codes (report 16 §4.3) ────────────────────────────────────────────────────────────────────────────

  public static final int METHOD_NOT_FOUND = -32601;
  public static final int INVALID_PARAMS = -32602;
  public static final int INTERNAL_ERROR = -32603;
  public static final int PLUGIN_NOT_LOADED = -32010;
  public static final int PLUGIN_LOAD_FAILED = -32011;
  public static final int HOST_BUSY = -32012;
  public static final int CANCELLED = -32013;
  public static final int DEADLINE_EXCEEDED = -32014;
  /** A secret asked outside a live call, with a foreign token, for a field the connection doesn't have. */
  public static final int SECRET_DENIED = -32020;
  public static final int SECRET_UNAVAILABLE = -32021;
  /** A connector's own refusal ({@code ConnectorException}); {@code data.kind} names its kind. */
  public static final int CONNECTOR_ERROR = -32030;

  private Gpp() {}
}
