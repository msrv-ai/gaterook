package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Exact file selection. Remote paths always use POSIX separators, regardless of this computer's OS. */
record SshLogSettings(SshSettings ssh, Path privateKeyPath, String root, List<String> allowedFiles, JsonNode logsConfig) {
  static SshLogSettings of(ConnectionConfig cfg, boolean selectionRequired) throws ConnectorException {
    String method = cfg.string("/credentials/method");
    Path key = null;
    if ("privateKeyFile".equals(method)) {
      String path = cfg.string("/credentials/privateKeyPath");
      if (path == null || path.isBlank() || path.length() > 4096 || control(path)) {
        throw ConnectorException.badInput("Enter the local private key file path.");
      }
      if (path.startsWith("~/")) {
        path = System.getProperty("user.home") + path.substring(1);
      }
      try {
        key = Path.of(path);
      } catch (RuntimeException e) {
        throw ConnectorException.badInput("The local private key path is invalid.");
      }
      if (!key.isAbsolute()) {
        throw ConnectorException.badInput("The private key path must be absolute or start with ~/.");
      }
    } else if (!"password".equals(method)) {
      throw ConnectorException.badInput("Choose password or private key file authentication.");
    }
    ObjectNode translated = ((ObjectNode) cfg.config()).deepCopy();
    ((ObjectNode) translated.path("credentials")).put("method", key == null ? "password" : "privateKey");
    SshSettings ssh = SshSettings.of(new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(),
        translated, cfg.secretFields(), cfg.metadata()));
    String root = cfg.string("/logs/root");
    if (root != null && !root.isBlank()) {
      root = absolute(root);
    } else if (selectionRequired) {
      throw ConnectorException.badInput("Choose a remote log folder.");
    }
    LinkedHashSet<String> files = new LinkedHashSet<>();
    for (var file : cfg.config().path("logs").path("allowedFiles")) {
      if (!file.isString()) {
        throw ConnectorException.badInput("Allowed log files must be relative file names.");
      }
      files.add(relative(file.asString()));
    }
    if (files.size() > 500 || (selectionRequired && files.isEmpty())) {
      throw ConnectorException.badInput("Select between 1 and 500 log files.");
    }
    return new SshLogSettings(ssh, key, root, List.copyOf(files), cfg.config().path("logs").deepCopy());
  }

  static String absolute(String path) throws ConnectorException {
    if (path == null || !path.startsWith("/") || path.length() > 4096 || control(path) || path.contains("\\")) {
      throw ConnectorException.badInput("The remote folder must be an absolute POSIX path.");
    }
    String result = path;
    while (result.length() > 1 && result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    if (!"/".equals(result)) {
      relative(result.substring(1));
    }
    return result;
  }

  static String relative(String path) throws ConnectorException {
    if (path == null || path.isBlank() || path.length() > 4096 || path.startsWith("/") || path.contains("\\")
        || control(path)) {
      throw ConnectorException.badInput("Log files must use a relative path inside the selected folder.");
    }
    for (String part : path.split("/", -1)) {
      if (part.isEmpty() || part.equals(".") || part.equals("..")) {
        throw ConnectorException.badInput("Log paths cannot contain empty, . or .. components.");
      }
    }
    return path;
  }

  static boolean control(String text) {
    return text.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
  }

  static String join(String root, String file) {
    return (root.equals("/") ? "" : root) + "/" + file;
  }

  String allowed(String file) throws ConnectorException {
    relative(file);
    if (!allowedFiles.contains(file)) {
      throw ConnectorException.denied("This log file is not selected for this connection.");
    }
    return join(root, file);
  }
}
