package ai.msrv.gaterook.core.clients;

/**
 * A config file MCP Gateway won't edit (report 03 §6 "Fail-closed configuration edits"): nothing is written, and the person
 * gets the reason, the position when the file doesn't parse, and the entry to add by hand when there is one.
 */
public final class ConfigEditException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why the edit was refused; the admin API passes the id on. */
  public enum Reason {
    /** Not valid strict JSON or TOML; {@link #line()} and {@link #column()} say where. */
    INVALID,
    /** JSON with comments or trailing commas: clients like Claude want strict JSON, and we'd lose the comments. */
    JSONC,
    /** The file, or the object entries live in, isn't the shape the client reads. */
    UNEXPECTED_SHAPE,
    /** {@code mcp_servers = { … }}: a TOML inline table can't take another table. */
    INLINE_TABLE,
    /** Larger than 1 MiB, or not a regular file. */
    UNSUPPORTED_FILE,
    /** The edit would change more than our entry; a bug, never written. */
    CHECK_FAILED
  }

  private final Reason reason;
  private final int line;
  private final int column;
  private final String fragment;

  public ConfigEditException(Reason reason, String message, int line, int column, String fragment) {
    super(message);
    this.reason = reason;
    this.line = line;
    this.column = column;
    this.fragment = fragment;
  }

  public ConfigEditException(Reason reason, String message) {
    this(reason, message, 0, 0, null);
  }

  public Reason reason() {
    return reason;
  }

  /** 1-based, or 0 when there is no position. */
  public int line() {
    return line;
  }

  public int column() {
    return column;
  }

  /** The entry to paste by hand, or {@code null}. */
  public String fragment() {
    return fragment;
  }

  /** The same refusal, now carrying the entry to add by hand. */
  public ConfigEditException withFragment(String text) {
    return new ConfigEditException(reason, getMessage(), line, column, text);
  }
}
