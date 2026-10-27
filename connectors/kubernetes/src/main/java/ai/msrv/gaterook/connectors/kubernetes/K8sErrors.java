package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.kubernetes.K8sApi.StatusException;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Error answers of the API server as the agent reads them. The server's own message is kept (it names the object,
 * the field, the missing permission), sanitized like any resource text; 401, 429 and 5xx say what to do instead.
 */
final class K8sErrors {

  private K8sErrors() {}

  /**
   * @param what the object the request was about ({@code deployment/api in app-dev}); used for 404
   */
  static ConnectorException of(StatusException e, String what) {
    String message = OutputSanitizer.sanitize(K8sText.cut(e.getMessage(), 1000));
    return switch (e.code) {
      case 401 -> ConnectorException.resourceError("The API server rejected the credentials of the connection (401 "
          + "Unauthorized): the token expired or was revoked. Replace it in MCP Gateway and test the connection.",
          null);
      case 403 -> ConnectorException.resourceError("Forbidden by the cluster's RBAC: " + message, null);
      case 404 -> ConnectorException.resourceError(what == null ? "Not found: " + message : what + " not found.",
          null);
      case 429 -> ConnectorException.unavailable("The API server is throttling requests (429). Try again in a "
          + "moment.", null);
      case 502, 503, 504 -> ConnectorException.unavailable("The API server is unavailable (" + e.code + "): "
          + message, null);
      default -> ConnectorException.resourceError(message, null);
    };
  }

  /** The fields of a server-side apply conflict: {@code .spec.replicas} owned by {@code kubectl-client-side-apply}. */
  static List<String> conflicts(StatusException e) {
    List<String> out = new ArrayList<>();
    for (JsonNode c : e.causes) {
      String field = c.path("field").asString("");
      String message = c.path("message").asString("");
      String owner = message.replaceFirst("(?s).*conflict with \"([^\"]+)\".*", "$1");
      out.add((field.isEmpty() ? message : field) + (owner.equals(message) ? "" : " (owned by " + owner + ")"));
    }
    return out;
  }
}
