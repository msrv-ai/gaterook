package ai.msrv.gaterook.core.admin;

import org.springframework.http.HttpStatus;

/** A request a controller refuses: status, title and, for a form, the JSON Pointer of the field at fault. */
public class ApiRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final HttpStatus status;
  private final String title;
  private final String field;

  public ApiRequestException(HttpStatus status, String title, String detail, String field) {
    super(detail);
    this.status = status;
    this.title = title;
    this.field = field;
  }

  public static ApiRequestException unprocessable(String title, String detail, String field) {
    return new ApiRequestException(HttpStatus.UNPROCESSABLE_CONTENT, title, detail, field);
  }

  public static ApiRequestException conflict(String title, String detail) {
    return new ApiRequestException(HttpStatus.CONFLICT, title, detail, null);
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }

  public String field() {
    return field;
  }
}
