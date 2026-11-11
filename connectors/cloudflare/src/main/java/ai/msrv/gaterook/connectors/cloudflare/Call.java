package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import java.nio.charset.StandardCharsets;

/** What one Cloudflare call works with: its connection, the connection's settings, the API client and its context. */
record Call(ConnectionConfig cfg, CloudflareSettings settings, CloudflareApi api, Zones zones, SecretResolver secrets,
    ExecContext ctx) {

  /** The bytes an answer may take; a quarter is left for masking and the core's own fields (as in GCP). */
  long budget() {
    return Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
  }

  static long utf8(String s) {
    return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
  }
}
