package ai.msrv.gaterook.core.admin;

/**
 * The optional {@code reason} of every change that writes a Setup change (ARCHITECTURE.md §6: "accepts an optional
 * reason (≤ 500)"), shown in the Reason column of Activity › Setup changes.
 */
public final class Reasons {

  public static final int MAX = 500;

  private Reasons() {}

  /**
   * @return the reason stripped, or {@code null} when blank
   * @throws ApiRequestException 422 at {@code /reason} when it is longer than {@value #MAX} characters
   */
  public static String check(String reason) {
    if (reason == null || reason.isBlank()) {
      return null;
    }
    String stripped = reason.strip();
    if (stripped.length() > MAX) {
      throw ApiRequestException.unprocessable("Reason too long", "A reason has at most 500 characters.", "/reason");
    }
    return stripped;
  }
}
