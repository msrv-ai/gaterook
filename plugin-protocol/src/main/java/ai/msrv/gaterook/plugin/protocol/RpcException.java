package ai.msrv.gaterook.plugin.protocol;

import tools.jackson.databind.JsonNode;

/**
 * A JSON-RPC error, either received from the other side or to be sent to it: {@code code}, {@code message} and
 * optional {@code data} (report 16 §4.3). Never carries a secret: messages are what the other side may log.
 */
public class RpcException extends Exception {

  private static final long serialVersionUID = 1L;

  private final int code;
  private final transient JsonNode data;

  public RpcException(int code, String message) {
    this(code, message, null);
  }

  public RpcException(int code, String message, JsonNode data) {
    super(message, null, false, false);
    this.code = code;
    this.data = data;
  }

  public int code() {
    return code;
  }

  /** The {@code data} member, or {@code null}. */
  public JsonNode data() {
    return data;
  }
}
