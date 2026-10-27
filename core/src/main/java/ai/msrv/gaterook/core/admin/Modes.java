package ai.msrv.gaterook.core.admin;

import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import java.util.Locale;

/**
 * Tool modes as the admin API writes and reads them: {@code OFF}, {@code READ_ONLY}, {@code APPROVAL}, {@code BYPASS} (ARCHITECTURE.md
 * §6 endpoint-detail); the UI shows the kit's labels. The modules hand some modes over as those labels already.
 */
public final class Modes {

  private Modes() {}

  /** {@code "Read only"}, {@code "read-only"} or {@code "READ_ONLY"} → {@code READ_ONLY}; {@code null} stays null. */
  public static String id(String labelOrId) {
    if (labelOrId == null) {
      return null;
    }
    return labelOrId.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
  }

  public static String id(Mode mode) {
    return mode == null ? null : mode.name();
  }

  /**
   * @throws ApiRequestException 422 at {@code field} for anything but the four modes
   */
  public static Mode parse(String id, String field) {
    try {
      return Mode.valueOf(id(id == null ? "" : id));
    } catch (IllegalArgumentException e) {
      throw ApiRequestException.unprocessable("Unknown mode", "A mode is OFF, READ_ONLY, APPROVAL or BYPASS.", field);
    }
  }
}
