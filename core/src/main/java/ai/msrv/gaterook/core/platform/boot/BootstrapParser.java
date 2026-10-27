package ai.msrv.gaterook.core.platform.boot;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.LaunchSecret;
import java.util.Arrays;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the bootstrap line (ARCHITECTURE.md §7.1). Strict about what the core relies on, lenient about fields it
 * doesn't know (a newer Electron may add some). Error messages never quote values: the line carries secrets.
 */
final class BootstrapParser {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,128}");
  private static final Pattern PROTECTION = Pattern.compile("[a-z0-9_]{1,32}");

  private BootstrapParser() {}

  static Bootstrap parse(byte[] line) {
    JsonNode root;
    try {
      root = JSON.readTree(line);
    } catch (JacksonException e) {
      throw invalid("the bootstrap line is not JSON");
    }
    if (root == null || !root.isObject()) {
      throw invalid("the bootstrap line is not a JSON object");
    }
    if (!root.path("v").isIntegralNumber() || root.path("v").asInt() != Bootstrap.VERSION) {
      throw invalid("unsupported bootstrap version (this core reads v" + Bootstrap.VERSION + ")");
    }
    LaunchSecret secret;
    try {
      secret = LaunchSecret.fromBase64Url(text(root, "launchSecret"));
    } catch (IllegalArgumentException e) {
      throw invalid("launchSecret must be at least " + LaunchSecret.MIN_KEY_BYTES + " bytes of base64url");
    }
    String nonce = text(root, "instanceNonce");
    if (!NONCE.matcher(nonce).matches()) {
      throw invalid("instanceNonce must be 16-128 base64url characters");
    }
    byte[] fileKey = null;
    JsonNode fileKeyNode = root.path("fileKey");
    if (!fileKeyNode.isMissingNode() && !fileKeyNode.isNull()) {
      if (!fileKeyNode.isString()) {
        throw invalid("fileKey must be a base64url string or null");
      }
      char[] encoded = fileKeyNode.asString().toCharArray();
      try {
        fileKey = Bootstrap.decodeFileKey(encoded);
      } catch (IllegalArgumentException e) {
        throw invalid("fileKey must be " + Bootstrap.FILE_KEY_BYTES + " bytes of base64url");
      } finally {
        Arrays.fill(encoded, '\0');
      }
    }
    String protection = root.path("protection").isString() ? root.path("protection").asString() : "off";
    if (!PROTECTION.matcher(protection).matches()) {
      throw invalid("protection is not a known value");
    }
    int port = port(root, "port");
    int publicPort = port(root, "publicPort");
    try {
      return new Bootstrap(secret, nonce, fileKey, protection, port, publicPort, false);
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage());
    } finally {
      if (fileKey != null) {
        Arrays.fill(fileKey, (byte) 0);
      }
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode node = root.path(field);
    if (!node.isString() || node.asString().isEmpty()) {
      throw invalid(field + " is missing");
    }
    return node.asString();
  }

  private static int port(JsonNode root, String field) {
    JsonNode node = root.path(field);
    if (!node.isIntegralNumber() || node.asInt() < 1 || node.asInt() > 65_535) {
      throw invalid(field + " must be a port number 1-65535");
    }
    return node.asInt();
  }

  private static GatewayStartupException invalid(String why) {
    return new GatewayStartupException(StartupReason.BOOTSTRAP_INVALID, "Invalid bootstrap: " + why);
  }
}
