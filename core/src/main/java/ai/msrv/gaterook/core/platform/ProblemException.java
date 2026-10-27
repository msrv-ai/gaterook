package ai.msrv.gaterook.core.platform;

import java.util.Objects;

/**
 * A change or lookup the core refuses for a reason the person can act on. The admin API turns it into
 * {@code application/problem+json} (ARCHITECTURE.md §6) with the status of its {@link Kind}; modules throw it so that
 * they don't depend on the web layer.
 *
 * <p>The message is for people: what happened and what to do, never a secret or an agent's text.
 */
public class ProblemException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The HTTP meaning: 404, 409, 422. */
  public enum Kind { NOT_FOUND, CONFLICT, UNPROCESSABLE }

  private final Kind kind;
  private final String title;
  private final String field;
  private final String confirm;

  public ProblemException(Kind kind, String title, String detail, String field, String confirm) {
    super(detail);
    this.kind = Objects.requireNonNull(kind, "kind");
    this.title = Objects.requireNonNull(title, "title");
    this.field = field;
    this.confirm = confirm;
  }

  public static ProblemException notFound(String title, String detail) {
    return new ProblemException(Kind.NOT_FOUND, title, detail, null, null);
  }

  public static ProblemException conflict(String title, String detail) {
    return new ProblemException(Kind.CONFLICT, title, detail, null, null);
  }

  /**
   * 409 that the client may resolve by repeating the request with {@code confirm} set to this value
   * ({@code send-secret-to-new-host}, {@code danger}).
   */
  public static ProblemException needsConfirmation(String title, String detail, String confirm) {
    return new ProblemException(Kind.CONFLICT, title, detail, null, Objects.requireNonNull(confirm, "confirm"));
  }

  /** 422; {@code field} is the JSON Pointer of the request field at fault, or {@code null}. */
  public static ProblemException unprocessable(String title, String detail, String field) {
    return new ProblemException(Kind.UNPROCESSABLE, title, detail, field, null);
  }

  public Kind kind() {
    return kind;
  }

  public String title() {
    return title;
  }

  public String field() {
    return field;
  }

  public String confirm() {
    return confirm;
  }
}
