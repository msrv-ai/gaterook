package ai.msrv.gaterook.connectors.access;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Help for the Access form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}), from this computer only, in
 * either scope: an Access file has no service to sign in to. The {@code .accdb} and {@code .mdb} databases of the typed
 * folder (or of the typed file's folder), and why the typed file can't be used. The file is the person's to choose, so
 * nothing is filled in.
 */
final class AccessSuggestions {

  static final String FILE = "/file";
  private static final int MAX_OPTIONS = 50;
  private static final int MAX_ENTRIES = 5_000;

  private AccessSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, ExecContext ctx) {
    Suggestions.Builder out = Suggestions.builder();
    String typed = cfg.string(FILE);
    if (typed == null || typed.isBlank()) {
      return out.build();
    }
    Path path;
    try {
      path = AccessFiles.path(typed);
    } catch (ConnectorException e) {
      // Not a path on this computer yet (relative, still being typed): the form's own check says so.
      return out.build();
    }
    boolean folderTyped = Files.isDirectory(path);
    Path folder = folderTyped ? path : path.getParent();
    if (folder == null || !Files.isDirectory(folder)) {
      return out.build();
    }
    Path home = AccessFiles.home(ctx == null ? null : ctx.workDir());
    List<Path> candidates = new ArrayList<>();
    try (DirectoryStream<Path> dir = Files.newDirectoryStream(folder)) {
      int seen = 0;
      for (Path p : dir) {
        if (++seen > MAX_ENTRIES) {
          break;
        }
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".accdb") || name.endsWith(".mdb")) {
          candidates.add(p);
        }
      }
    } catch (IOException | DirectoryIteratorException | SecurityException e) {
      return out.build();
    }
    candidates.sort(null);
    List<Option> options = new ArrayList<>();
    for (Path p : candidates) {
      if (options.size() == MAX_OPTIONS) {
        break;
      }
      try {
        Path real = AccessFiles.database(p.toString(), home);
        options.add(new Option(shown(p), p.getFileName().toString(), AccessFiles.format(real)));
      } catch (ConnectorException e) {
        // Not an Access database, too large, or GateRook's own: not offered.
      }
    }
    out.options(FILE, "this computer", options);
    if (folderTyped) {
      return out.build();
    }
    if (!Files.exists(path)) {
      out.note(shown(path) + " doesn't exist on this computer" + (options.isEmpty() ? "."
          : ": choose a database file of its folder."));
      return out.build();
    }
    try {
      AccessFiles.database(typed, home);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
    }
    return out.build();
  }

  /** {@code ~/Documents/orders.accdb} rather than the home folder's full path, as people type it. */
  static String shown(Path p) {
    Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    return p.startsWith(home) && !p.equals(home) ? "~/" + home.relativize(p).toString().replace('\\', '/')
        : p.toString();
  }
}
