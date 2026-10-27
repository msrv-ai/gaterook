package ai.msrv.gaterook.core.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The error body of the admin API, {@code application/problem+json} (ARCHITECTURE.md §6): what went wrong
 * ({@code title}), what to do ({@code detail}), and for a form the field at fault as a JSON Pointer of the request.
 */
public record ApiProblem(String type, String title, String detail, String field, List<FieldError> errors,
    @JsonInclude(JsonInclude.Include.NON_NULL) String confirm) {

  public ApiProblem(String type, String title, String detail, String field, List<FieldError> errors) {
    this(type, title, detail, field, errors, null);
  }

  /** The value the client repeats as {@code confirm} to go ahead (ARCHITECTURE.md §6, {@code send-secret-to-new-host}). */
  public ApiProblem withConfirm(String value) {
    return new ApiProblem(type, title, detail, field, errors, value);
  }

  /** One invalid field of a form. */
  public record FieldError(String field, String message) {}

  public static ApiProblem of(String title, String detail) {
    return new ApiProblem("about:blank", title, detail, null, List.of());
  }

  public static ApiProblem field(String title, String detail, String field) {
    return new ApiProblem("about:blank", title, detail, field, field == null ? List.of()
        : List.of(new FieldError(field, detail)));
  }
}
