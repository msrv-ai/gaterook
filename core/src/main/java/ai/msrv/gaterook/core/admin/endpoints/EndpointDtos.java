package ai.msrv.gaterook.core.admin.endpoints;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.RiskAnalyzer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** The endpoint DTOs of the admin API (ARCHITECTURE.md §6 endpoints, endpoint-detail), shared with Overview. */
public final class EndpointDtos {

  /** {@code read}: Read only pairs; tools are counted per tool × connection, core tools included. */
  public record ToolCounts(int read, int approval, int off, int bypass) {
    public ToolCounts(int read, int approval, int off) {
      this(read, approval, off, 0);
    }
  }

  public record ConnectionCounts(int total, Map<String, Integer> byEnv) {}

  public record TokenCounts(int active, int expiringSoon) {}

  /**
   * The kit's {@code risk-callout}.
   *
   * @param state      {@code present}, {@code limited}, {@code partial}, {@code none} or {@code dismissed}
   * @param underlying what the callout would say without the dismissal
   * @param present    the ingredients that are there ({@code privateData}, {@code untrustedContent},
   *                   {@code externalChannel})
   */
  public record Risk(String state, String underlying, List<String> present, Ingredients ingredients,
      List<Source> sources, boolean published, JsonNode dismissed) {}

  /** Each {@code present} or {@code absent}; the channel also {@code limited} (fixed runbooks only). */
  public record Ingredients(String privateData, String untrustedContent, String externalChannel) {}

  /** Why an ingredient is there: "sql_query reads dev-db". */
  public record Source(String ingredient, String tool, String connection, String text) {}

  /**
   * One row of Endpoints.
   *
   * @param status        {@code running} or {@code disabled}
   * @param blockedReason why it serves nothing now (a row changed outside GateRook), or {@code null}
   */
  public record Summary(String slug, String description, String url, String env, String access, String status,
      ToolCounts tools, ConnectionCounts connections, Map<String, Integer> health, long approvalsWaiting,
      List<String> clients, TokenCounts tokens, boolean published, Risk risk, String blockedReason, long version) {}

  private EndpointDtos() {}

  public static Summary summary(EndpointService.Summary s, long approvalsWaiting, List<String> clients) {
    Map<String, Integer> byEnv = new LinkedHashMap<>();
    for (Env e : Env.values()) {
      byEnv.put(e.id(), s.connectionsByEnv().getOrDefault(e, 0));
    }
    return new Summary(s.slug(), s.description(), s.url(), s.env().id(), s.access(), s.status(),
        new ToolCounts(s.tools().read(), s.tools().approval(), s.tools().off(), s.tools().bypass()),
        new ConnectionCounts(s.connections(), byEnv), s.health(), approvalsWaiting, clients,
        new TokenCounts(s.tokensActive(), s.tokensExpiringSoon()), s.published(), risk(s.risk()), s.blockedReason(),
        s.version());
  }

  public static Risk risk(RiskAnalyzer.Report r) {
    RiskAnalyzer.Ingredients i = r.ingredients();
    List<String> present = new java.util.ArrayList<>();
    if (i.privateData()) {
      present.add("privateData");
    }
    if (i.untrustedContent()) {
      present.add("untrustedContent");
    }
    if (!"absent".equals(i.externalChannel())) {
      present.add("externalChannel");
    }
    return new Risk(r.state(), r.underlying(), List.copyOf(present), new Ingredients(i.privateData() ? "present"
        : "absent", i.untrustedContent() ? "present" : "absent", i.externalChannel()),
        r.sources().stream().map(x -> new Source(x.ingredient(), x.tool(), x.connection(), x.text())).toList(),
        r.published(), r.dismissed());
  }
}
