package com.gaterook.connectors.azure;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * What one Azure call works with: its connection, the connection's settings, the environment's Cloud policy as
 * {@code classify} saw it, the Resource Manager client of the call and its context.
 */
record Call(ConnectionConfig cfg, Settings settings, AzurePolicy policy, Arm arm, ExecContext ctx) {

  /**
   * GateRook's share of Resource Manager reads per subscription and minute. Resource Manager throttles per principal,
   * subscription and region; the share leaves room for people and other tools.
   */
  static final int ARM_PER_MINUTE = 300;
  /** Resource Graph allows about 15 queries per 5 seconds per user; GateRook takes a quarter of that. */
  static final int GRAPH_PER_MINUTE = 45;
  /** Azure Monitor metrics, alerts and the Activity Log per subscription and minute. */
  static final int MONITOR_PER_MINUTE = 200;
  /** Time kept for building the answer: no new page starts after it. */
  static final Duration LAST_PAGE = Duration.ofMillis(1_500);

  /** One read of {@code api} in {@code subscription} from GateRook's share, or a refusal to tell the agent. */
  void quota(String subscription, String api, int perMinute) throws ConnectorException {
    if (!ctx.acquire("azure:" + subscription + ":" + api, perMinute)) {
      throw ConnectorException.resourceError("GateRook's share of " + api + " reads for subscription " + subscription
          + " (" + perMinute + " per minute) is used up. Try again in a minute.", null);
    }
  }

  boolean timeForAnotherPage() {
    return ctx.remaining().compareTo(LAST_PAGE) > 0;
  }

  /** The bytes an answer may take; a quarter is left for masking and the core's own fields. */
  long budget() {
    return Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
  }

  static long utf8(String s) {
    return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
  }
}
