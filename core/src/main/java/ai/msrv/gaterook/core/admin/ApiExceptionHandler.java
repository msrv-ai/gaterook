package ai.msrv.gaterook.core.admin;

import ai.msrv.gaterook.core.platform.DiskFullException;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.secrets.MasterKeyException;
import ai.msrv.gaterook.core.secrets.SecretService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors of the admin API as {@link ApiProblem}s (ARCHITECTURE.md §6): 404 for what doesn't exist, 409 for state
 * conflicts (stale version, already rotating, locked), 422 for requests the rules refuse, 400 for bodies that don't
 * parse, 507 while the disk is full. Messages are written for people and never carry secret values.
 */
@RestControllerAdvice(basePackages = "ai.msrv.gaterook.core.admin")
@RegisterReflectionForBinding({ApiProblem.class, ApiProblem.FieldError.class})
class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

  @ExceptionHandler(MasterKeyException.class)
  ResponseEntity<ApiProblem> masterKey(MasterKeyException e) {
    HttpStatus status = switch (e.problem()) {
      case LOCKED, NOT_LOCKED, ROTATION_RUNNING -> HttpStatus.CONFLICT;
      case BAD_BACKUP, MISSING_KEYS, NOT_CONFIRMED, IO -> HttpStatus.UNPROCESSABLE_CONTENT;
    };
    return problem(status, title(e.problem()), e.getMessage(), null);
  }

  @ExceptionHandler(SecretService.NoSuchSecretException.class)
  ResponseEntity<ApiProblem> noSecret(SecretService.NoSuchSecretException e) {
    return problem(HttpStatus.NOT_FOUND, "Secret not found", e.getMessage(), null);
  }

  @ExceptionHandler(SecretService.SecretRuleException.class)
  ResponseEntity<ApiProblem> secretRule(SecretService.SecretRuleException e) {
    return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Can't change this secret", e.getMessage(), e.field());
  }

  @ExceptionHandler(ProblemException.class)
  ResponseEntity<ApiProblem> module(ProblemException e) {
    HttpStatus status = switch (e.kind()) {
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case CONFLICT -> HttpStatus.CONFLICT;
      case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
    };
    ResponseEntity<ApiProblem> response = problem(status, e.title(), e.getMessage(), e.field());
    return e.confirm() == null ? response : ResponseEntity.status(status).contentType(PROBLEM)
        .body(response.getBody().withConfirm(e.confirm()));
  }

  @ExceptionHandler(ApiRequestException.class)
  ResponseEntity<ApiProblem> request(ApiRequestException e) {
    return problem(e.status(), e.title(), e.getMessage(), e.field());
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ApiProblem> stale(OptimisticLockingFailureException e) {
    return problem(HttpStatus.CONFLICT, "Changed meanwhile", "Someone changed this in the meantime. Reload and try "
        + "again.", null);
  }

  @ExceptionHandler(DiskFullException.class)
  ResponseEntity<ApiProblem> diskFull(DiskFullException e) {
    return problem(HttpStatus.INSUFFICIENT_STORAGE, "The disk is full", "MCP Gateway couldn't save this change because "
        + "the disk is full. Free up some space, then try again.", null);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiProblem> unreadable(HttpMessageNotReadableException e) {
    // The parser's message may quote the body, and the body may hold a secret: never echo it.
    return problem(HttpStatus.BAD_REQUEST, "Bad request", "The request body isn't the JSON this call expects.", null);
  }

  private static String title(MasterKeyException.Problem problem) {
    return switch (problem) {
      case LOCKED -> "MCP Gateway is locked";
      case NOT_LOCKED -> "The master key works";
      case ROTATION_RUNNING -> "Already rotating";
      case BAD_BACKUP -> "Can't read the backup";
      case MISSING_KEYS -> "The backup doesn't fit";
      case NOT_CONFIRMED -> "Confirmation needed";
      case IO -> "Can't write or read the file";
    };
  }

  static ResponseEntity<ApiProblem> problem(HttpStatus status, String title, String detail, String field) {
    if (status.is5xxServerError()) {
      log.warn("{}: {}", title, detail);
    }
    return ResponseEntity.status(status).contentType(PROBLEM).body(ApiProblem.field(title, detail, field));
  }
}
