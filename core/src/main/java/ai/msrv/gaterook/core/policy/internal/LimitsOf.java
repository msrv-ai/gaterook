package ai.msrv.gaterook.core.policy.internal;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import java.time.Duration;
import tools.jackson.databind.JsonNode;

/**
 * {@link Limits} from merged policy sections (BLUEPRINT §9: dev 1,000 rows · 5 s · 1,000 lines · 512 KB · 10 s; prod
 * 100 rows · 3 s · 200 lines · 256 KB · 5 s). A section the core doesn't know (a connector module that isn't part of
 * this build) falls back to the defaults of its environment.
 */
public final class LimitsOf {

  /** Cells longer than this are cut (report 04 §3.3); not a policy setting. */
  public static final int MAX_CELL_CHARS = 8 * 1024;

  private LimitsOf() {}

  public static Limits from(JsonNode sections, Env env) {
    boolean prod = env == Env.PROD;
    JsonNode sql = sections.path("sql");
    JsonNode ssh = sections.path("ssh");
    JsonNode http = sections.path("http");
    JsonNode logs = sections.path("logs");
    JsonNode limits = sections.path("limits");
    long responseKb = Math.min(limits.path("maxResponseKb").asLong(prod ? 256 : 512),
        Math.min(sql.path("maxResponseKb").asLong(Long.MAX_VALUE), http.path("maxResponseKb").asLong(Long.MAX_VALUE)));
    long toolTimeout = Math.min(limits.path("toolTimeoutSeconds").asLong(prod ? 5 : 10),
        http.path("toolTimeoutSeconds").asLong(Long.MAX_VALUE));
    return new Limits(
        sql.path("rowLimit").asInt(prod ? 100 : 1_000),
        limits.path("logLines").asInt(prod ? 200 : 1_000),
        responseKb * 1024,
        http.path("maxRequestKb").asLong(prod ? 64 : 256) * 1024,
        ssh.path("maxOutputKb").asLong(256) * 1024,
        ssh.path("maxStderrKb").asLong(64) * 1024,
        MAX_CELL_CHARS,
        Duration.ofMillis(sql.path("statementTimeoutMs").asLong(prod ? 3_000 : 5_000)),
        Duration.ofSeconds(ssh.path("commandTimeoutSeconds").asLong(30)),
        Duration.ofSeconds(toolTimeout),
        Duration.ofHours(logs.path("maxRangeHours").asLong(prod ? 24 : 168)));
  }

  /** The per-minute rate limits of the Limits section: per principal and per principal × tool. */
  public static int ratePerToken(JsonNode sections, Env env) {
    return sections.path("limits").path("rateLimitPerToken").asInt(env == Env.PROD ? 60 : 120);
  }

  public static int ratePerTool(JsonNode sections, Env env) {
    return sections.path("limits").path("rateLimitPerTool").asInt(env == Env.PROD ? 20 : 30);
  }
}
