package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.impl.AbstractSftpClient;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Read-only SFTP operations. Selection checks run again immediately before opening a file. */
final class SshLogFiles {
  private static final int MAX_ENTRIES = 500;

  private SshLogFiles() {}

  static void budget(SftpClient sftp, ExecContext ctx) throws ConnectorException {
    if (ctx.cancelled() || Thread.currentThread().isInterrupted() || ctx.remaining().isZero()) {
      throw ConnectorException.timeout("The SSH log operation timed out or was cancelled.");
    }
    AbstractSftpClient.SFTP_CLIENT_CMD_TIMEOUT.set(sftp.getClientSession(), ctx.remaining());
  }

  static ObjectNode browse(SftpClient sftp, String requested, ExecContext ctx)
      throws IOException, ConnectorException {
    String path = SshLogSettings.absolute(requested);
    directory(sftp, path, ctx);
    ObjectNode result = JsonNodeFactory.instance.objectNode().put("path", path);
    if (path.equals("/")) {
      result.putNull("parent");
    } else {
      int slash = path.lastIndexOf('/');
      result.put("parent", slash == 0 ? "/" : path.substring(0, slash));
    }
    List<ObjectNode> entries = new ArrayList<>();
    boolean truncated = false;
    int seen = 0;
    int bytes = 0;
    int byteLimit = (int) Math.min(256 * 1024, Math.max(1024, ctx.limits().maxResponseBytes() / 2));
    try (var handle = sftp.openDir(path)) {
      outer: while (true) {
        budget(sftp, ctx);
        List<SftpClient.DirEntry> page = sftp.readDir(handle);
        if (page == null || page.isEmpty()) {
          break;
        }
        for (var entry : page) {
          String name = entry.getFilename();
          if (++seen > MAX_ENTRIES || bytes + name.length() * 6 + path.length() * 6 + 128 > byteLimit) {
            truncated = true;
            break outer;
          }
          if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")
              || name.isBlank() || SshLogSettings.control(name)) {
            continue;
          }
          var attr = entry.getAttributes();
          if (attr.isSymbolicLink() || (!attr.isDirectory() && !attr.isRegularFile())) {
            continue;
          }
          bytes += name.length() * 6 + path.length() * 6 + 128;
          entries.add(JsonNodeFactory.instance.objectNode().put("name", name)
              .put("path", SshLogSettings.join(path, name))
              .put("type", attr.isDirectory() ? "directory" : "file").put("size", attr.getSize()));
        }
      }
    }
    entries.sort(Comparator.comparing((ObjectNode n) -> n.path("type").asString())
        .thenComparing(n -> n.path("name").asString()));
    ArrayNode out = result.putArray("entries");
    entries.forEach(out::add);
    return result.put("truncated", truncated);
  }

  static void directory(SftpClient sftp, String path, ExecContext ctx) throws IOException, ConnectorException {
    noLinks(sftp, path, ctx);
    budget(sftp, ctx);
    if (!sftp.lstat(path).isDirectory()) {
      throw ConnectorException.badInput("The selected remote path is not a directory.");
    }
  }

  /** Reject links in every component, including the selected root; canonical comparison catches aliases. */
  private static void noLinks(SftpClient sftp, String path, ExecContext ctx) throws IOException, ConnectorException {
    String at = "";
    for (String part : path.substring(1).split("/")) {
      if (part.isEmpty()) {
        continue;
      }
      at += "/" + part;
      budget(sftp, ctx);
      if (sftp.lstat(at).isSymbolicLink()) {
        throw ConnectorException.denied("Symbolic links are not allowed in selected log paths.");
      }
    }
    budget(sftp, ctx);
    if (!SshLogSettings.absolute(sftp.canonicalPath(path)).equals(path)) {
      throw ConnectorException.denied("The remote path resolves outside its selected location.");
    }
  }

  static SftpClient.Attributes file(SftpClient sftp, SshLogSettings settings, String file, ExecContext ctx)
      throws IOException, ConnectorException {
    String path = settings.allowed(file);
    noLinks(sftp, path, ctx);
    budget(sftp, ctx);
    var attributes = sftp.lstat(path);
    if (!attributes.isRegularFile()) {
      throw ConnectorException.denied("Only regular log files can be read.");
    }
    return attributes;
  }

  /**
   * A connection test checks the shared root's ancestry before and after the batch. Check every relative component
   * before and after opening each file without repeating the same root lstat requests. Canonical comparison alone
   * is insufficient: some SFTP servers return normalized paths without resolving symbolic parents.
   * Tool reads keep the full per-component checks in {@link #file}.
   */
  static void inspectFile(SftpClient sftp, SshLogSettings settings, String file, ExecContext ctx)
      throws IOException, ConnectorException {
    String path = settings.allowed(file);
    String at = settings.root();
    String[] parts = file.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      at = SshLogSettings.join(at, parts[i]);
      budget(sftp, ctx);
      if (sftp.lstat(at).isSymbolicLink()) {
        throw ConnectorException.denied("Symbolic links are not allowed in selected log paths.");
      }
    }
    budget(sftp, ctx);
    if (!SshLogSettings.absolute(sftp.canonicalPath(path)).equals(path)) {
      throw ConnectorException.denied("Symbolic links are not allowed in selected log paths.");
    }
    budget(sftp, ctx);
    if (!sftp.lstat(path).isRegularFile()) {
      throw ConnectorException.denied("Only regular log files can be read.");
    }
  }

  static ToolResult list(SftpClient sftp, SshLogSettings settings, ExecContext ctx)
      throws IOException, ConnectorException {
    ObjectNode result = JsonNodeFactory.instance.objectNode().put("root", settings.root());
    ArrayNode files = result.putArray("files");
    int max = Math.max(1, Math.min(MAX_ENTRIES, ctx.limits().maxRows()));
    long bytes = 128 + settings.root().length() * 6L;
    long byteLimit = Math.max(1, ctx.limits().maxResponseBytes() / 2);
    if (bytes > byteLimit) {
      throw ConnectorException.badInput("The response limit is too small to list this log folder.");
    }
    for (String file : settings.allowedFiles()) {
      if (files.size() == max || bytes + file.length() * 6L + 80 > byteLimit) {
        result.put("truncated", true);
        return ToolResult.json(result, null).truncated(files.size() == max ? "row_limit" : "size_limit");
      }
      bytes += file.length() * 6L + 80;
      var attributes = file(sftp, settings, file, ctx);
      files.addObject().put("file", file).put("size", attributes.getSize());
    }
    result.put("truncated", false);
    return ToolResult.json(result, null);
  }

}
