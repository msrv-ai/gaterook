package ai.msrv.gaterook.connectors.duckdb;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Help for the DuckDB form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}), from this computer only: a DuckDB
 * file has no service to sign in to, so both scopes look at the files. The DuckDB databases of the typed folder (or of
 * the typed file's folder), and for a database file the subfolders next to it that hold Parquet, CSV or JSON files, as
 * data folders: the database's own folder can't be one, since DuckDB could write the database through it. Both fields
 * are the person's to choose, so nothing is filled in.
 */
final class DuckDbSuggestions {

  static final String FILE = "/file";
  static final String FOLDERS = "/dataFolders";
  private static final Set<String> DATABASES = Set.of("duckdb", "ddb", "db");
  private static final Map<String, String> DATA = Map.of("parquet", "Parquet", "csv", "CSV", "tsv", "CSV",
      "json", "JSON", "jsonl", "JSON", "ndjson", "JSON");
  private static final int MAX_OPTIONS = 50;
  private static final int MAX_ENTRIES = 5_000;
  /** Subfolders looked into for data files, and entries read in each: a suggestion stays a few milliseconds. */
  private static final int MAX_FOLDERS = 30;
  private static final int MAX_FOLDER_ENTRIES = 1_000;

  private DuckDbSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, ExecContext ctx) {
    Suggestions.Builder out = Suggestions.builder();
    String typed = cfg.string(FILE);
    if (typed == null || typed.isBlank()) {
      return out.build();
    }
    Path path;
    try {
      path = DuckDbFiles.path(typed, "database file");
    } catch (ConnectorException e) {
      // Not a path on this computer yet (relative, still being typed): the form's own check says so.
      return out.build();
    }
    boolean folderTyped = Files.isDirectory(path);
    Path folder = folderTyped ? path : path.getParent();
    if (folder == null || !Files.isDirectory(folder)) {
      return out.build();
    }
    Path home = DuckDbFiles.home(ctx == null ? null : ctx.workDir());
    List<Path> entries = entries(folder);
    List<Option> databases = new ArrayList<>();
    for (Path p : entries) {
      if (databases.size() < MAX_OPTIONS && Files.isRegularFile(p) && DATABASES.contains(extension(p))
          && duckDb(p) && !gaterooks(p, home)) {
        databases.add(new Option(shown(p), p.getFileName().toString(), p.equals(path) ? "chosen" : null));
      }
    }
    out.options(FILE, "this computer", databases);
    if (folderTyped) {
      return out.build();
    }
    if (!Files.exists(path)) {
      out.note(shown(path) + " doesn't exist on this computer" + (databases.isEmpty() ? "."
          : ": choose a database file of its folder."));
      return out.build();
    }
    try {
      DuckDbFiles.database(typed, home);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return out.build();
    }
    List<Option> folders = new ArrayList<>();
    int looked = 0;
    for (Path p : entries) {
      if (looked == MAX_FOLDERS || folders.size() == MAX_OPTIONS) {
        break;
      }
      if (Files.isDirectory(p) && !p.getFileName().toString().startsWith(".") && !gaterooks(p, home)) {
        looked++;
        String files = dataFiles(entries(p, MAX_FOLDER_ENTRIES));
        if (files != null) {
          folders.add(new Option(shown(p), p.getFileName() + "/", files));
        }
      }
    }
    out.options(FOLDERS, "this computer", folders);
    String beside = dataFiles(entries);
    if (beside != null) {
      out.note("Data files next to " + path.getFileName() + " (" + beside + ") can't be read from its folder, which "
          + "holds the database: move them into a subfolder and choose that as a data folder.");
    }
    return out.build();
  }

  /** {@code 12 Parquet files, 3 CSV files}, or {@code null} when the folder holds none. */
  static String dataFiles(List<Path> entries) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Path p : entries) {
      String kind = DATA.get(extension(p));
      if (kind != null && Files.isRegularFile(p)) {
        counts.merge(kind, 1, Integer::sum);
      }
    }
    if (counts.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (String kind : List.of("Parquet", "CSV", "JSON")) {
      Integer n = counts.get(kind);
      if (n != null) {
        parts.add(n + " " + kind + (n == 1 ? " file" : " files"));
      }
    }
    return String.join(", ", parts);
  }

  /** The folder's entries in name order, at most {@link #MAX_ENTRIES} of them; none when it can't be read. */
  private static List<Path> entries(Path folder) {
    return entries(folder, MAX_ENTRIES);
  }

  private static List<Path> entries(Path folder, int max) {
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> dir = Files.newDirectoryStream(folder)) {
      for (Path p : dir) {
        if (out.size() == max) {
          break;
        }
        out.add(p);
      }
    } catch (IOException | DirectoryIteratorException | SecurityException e) {
      return List.of();
    }
    out.sort(null);
    return out;
  }

  private static String extension(Path p) {
    String name = p.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** A file that starts as a DuckDB database does: its header's magic at byte 8. */
  private static boolean duckDb(Path p) {
    byte[] head = new byte[DuckDbFiles.MAGIC_OFFSET + DuckDbFiles.MAGIC.length];
    try (InputStream in = Files.newInputStream(p)) {
      return in.readNBytes(head, 0, head.length) == head.length && Arrays.equals(head, DuckDbFiles.MAGIC_OFFSET,
          head.length, DuckDbFiles.MAGIC, 0, DuckDbFiles.MAGIC.length);
    } catch (IOException | SecurityException e) {
      return false;
    }
  }

  private static boolean gaterooks(Path p, Path home) {
    if (home == null) {
      return false;
    }
    try {
      return DuckDbFiles.gaterooks(p.toRealPath(), home);
    } catch (IOException | SecurityException e) {
      return true;
    }
  }

  /** {@code ~/projects/warehouse.duckdb} rather than the home folder's full path, as people type it. */
  static String shown(Path p) {
    Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    return p.startsWith(home) && !p.equals(home) ? "~/" + home.relativize(p).toString().replace('\\', '/')
        : p.toString();
  }
}
