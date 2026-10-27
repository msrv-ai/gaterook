package ai.msrv.gaterook.core.admin.core;

import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.platform.FileGrants;
import java.nio.file.Path;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/files/grant} (ARCHITECTURE.md §6, Electron main only): registers a path the person chose in a
 * native dialog and returns the one-time token the renderer passes on ({@code saveToken}, {@code openToken}). The
 * renderer can't reach {@code /internal/**}; the API never takes a raw path from it.
 */
@RestController
@RegisterReflectionForBinding(FileGrantController.Granted.class)
class FileGrantController {

  record Grant(String path, String purpose) {}

  record Granted(String token, long expiresInSeconds) {}

  private final FileGrants grants;

  FileGrantController(FileGrants grants) {
    this.grants = grants;
  }

  @PostMapping("/internal/files/grant")
  Granted grant(@RequestBody Grant request) {
    FileGrants.Purpose purpose;
    try {
      purpose = FileGrants.Purpose.of(request.purpose() == null ? "" : request.purpose());
    } catch (IllegalArgumentException e) {
      throw ApiRequestException.unprocessable("Unknown purpose", "No file purpose " + request.purpose(), "/purpose");
    }
    try {
      Path path = Path.of(request.path() == null ? "" : request.path());
      return new Granted(grants.grant(path, purpose), FileGrants.TTL.toSeconds());
    } catch (IllegalArgumentException e) {
      throw ApiRequestException.unprocessable("Can't use this file", e.getMessage(), "/path");
    }
  }
}
