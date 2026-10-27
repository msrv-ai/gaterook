package ai.msrv.gaterook.core.mcp.internal;

/**
 * The two protocol eras one URL serves (ARCHITECTURE.md A2): {@code 2026-07-28} without sessions, version and
 * capabilities in every request's {@code _meta}; and {@code 2025-11-25} with {@code initialize} (2025-06-18 and
 * 2025-03-26 are answered the same way: the fields are additive).
 */
public enum Era {
  LEGACY, MODERN;

  public static final String MODERN_VERSION = "2026-07-28";
  public static final String LEGACY_VERSION = "2025-11-25";

  /** What the endpoint setting {@code protocol.revisions} names for this era. */
  public String revision() {
    return this == MODERN ? MODERN_VERSION : LEGACY_VERSION;
  }
}
