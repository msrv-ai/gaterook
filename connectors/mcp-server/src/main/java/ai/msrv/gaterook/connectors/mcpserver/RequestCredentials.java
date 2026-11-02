package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * What authorizes each request of an HTTP session from cloud credentials the person gave the server (ARCHITECTURE.md
 * §7.6): a Google access token with its billing project, or an AWS SigV4 signature. The runtime makes them itself, so
 * unlike a sign-in nothing is asked of the person: a refusal means these credentials may not do it.
 */
interface RequestCredentials {

  /**
   * The headers that authorize one request as it goes out.
   *
   * @param url  the URL exactly as it is sent
   * @param body the body as it is sent; empty for none
   * @throws ConnectorException {@code RESOURCE_UNAVAILABLE} when no token or credential can be had, with what to do
   */
  Map<String, String> headers(String method, URI url, byte[] body) throws ConnectorException;

  /**
   * The server refused the last headers (HTTP 401). Renews what can be renewed.
   *
   * @return whether the refused request may go once more
   */
  boolean refused();

  /** The values in use now that would reveal the credentials (a token, a secret key), for masking. */
  List<String> secrets();

  /** What a refusal names: "Google Cloud credentials", "AWS credentials". */
  String label();
}
