package ai.msrv.gaterook.core.admin.endpoints;

import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.endpoints.EndpointShareService;
import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Only desktop main can access decrypted portable bundles; the renderer cannot proxy internal routes. */
@RestController
@RegisterReflectionForBinding({EndpointSharesController.Export.class, EndpointSharesController.Preview.class,
    EndpointSharesController.Import.class})
class EndpointSharesController {
  record Export(String endpointSlug, List<String> connectionNames, boolean includeSecrets) {}
  record Preview(JsonNode bundle) { @Override public String toString() { return "Preview[bundle=<redacted>]"; } }
  record Import(JsonNode bundle, List<String> connectionIds, boolean includeEndpoint, boolean includeSecrets,
      String endpointSlug, String reason) { @Override public String toString() { return "Import[bundle=<redacted>]"; } }
  private final EndpointShareService shares;
  EndpointSharesController(EndpointShareService shares) { this.shares = shares; }

  @PostMapping("/internal/endpoint-shares/export")
  ResponseEntity<JsonNode> export(@RequestBody Export request) {
    return response(shares.export(request.endpointSlug(), request.connectionNames(), request.includeSecrets()));
  }
  @PostMapping("/internal/endpoint-shares/preview")
  ResponseEntity<JsonNode> preview(@RequestBody Preview request) { return response(shares.preview(request.bundle())); }
  @PostMapping("/internal/endpoint-shares/import")
  ResponseEntity<JsonNode> importBundle(@RequestBody Import request) {
    return response(shares.importBundle(request.bundle(), request.connectionIds(), request.includeEndpoint(),
        request.includeSecrets(), request.endpointSlug(), Reasons.check(request.reason())));
  }
  private static ResponseEntity<JsonNode> response(JsonNode value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }
}
