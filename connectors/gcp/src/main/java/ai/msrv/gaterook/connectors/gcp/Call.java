package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * What one GCP call works with: its connection, the connection's settings, the environment's Cloud policy as
 * {@code classify} saw it, the Google client of the call and its context.
 */
record Call(ConnectionConfig cfg, Settings settings, GcpPolicy policy, GoogleApi api, CloudEndpoints endpoints,
    ExecContext ctx) {

  /** Reads of Cloud Asset per minute the gateway takes from the consumer project's 400 (report 09 §6 "Quotas"). */
  static final int ASSET_PER_MINUTE = 100;
  /** Reads of Cloud Monitoring per minute per project (the real quota is UNVERIFIED, report 09 §9). */
  static final int MONITORING_PER_MINUTE = 300;
  /** Time kept for building the answer: no new page starts after it. */
  static final Duration LAST_PAGE = Duration.ofMillis(1_500);

  /** The base URL of a Google API: its test endpoint in a development build, else the real one. */
  URI base(String key, URI real) {
    return GoogleApi.base(endpoints, key, real);
  }

  /** One read of Cloud Asset from the shared quota of the consumer project, or a refusal to tell the agent. */
  void assetQuota() throws ConnectorException {
    String project = settings.consumerProject();
    if (!ctx.acquire("gcp:" + project + ":asset", ASSET_PER_MINUTE)) {
      throw ConnectorException.resourceError("MCP Gateway's share of Cloud Asset reads for " + project + " ("
          + ASSET_PER_MINUTE + " per minute) is used up. Try again in a minute.", null);
    }
  }

  void monitoringQuota(String project) throws ConnectorException {
    if (!ctx.acquire("gcp:" + project + ":monitoring", MONITORING_PER_MINUTE)) {
      throw ConnectorException.resourceError("MCP Gateway's share of Cloud Monitoring reads for " + project + " ("
          + MONITORING_PER_MINUTE + " per minute) is used up. Try again in a minute.", null);
    }
  }

  /** Whether another page fits before the deadline. */
  boolean timeForAnotherPage() {
    return ctx.remaining().compareTo(LAST_PAGE) > 0;
  }

  /** The bytes an answer may take; a quarter is left for masking and the core's own fields (as in Logs). */
  long budget() {
    return Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
  }

  static long utf8(String s) {
    return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
  }

  /** A query parameter, URL-encoded. */
  static String param(String name, String value) {
    return name + "=" + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
