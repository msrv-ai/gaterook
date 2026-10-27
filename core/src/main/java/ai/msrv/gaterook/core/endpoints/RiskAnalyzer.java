package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.ToolDefinition.Egress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The risk callout of an endpoint (kit {@code risk-callout}, ARCHITECTURE.md §9 "Prompt-injection-safe approvals"): one
 * endpoint that reads private data, takes in untrusted content and can send data out lets an agent that read untrusted
 * text be steered into leaking private data. Only pairs that are not Off count; each tool declares its ingredients
 * ({@code ToolDefinition.Risk}). A channel of fixed runbooks only is "limited": still a channel, the combination is
 * "contained"; a runbook with a free-text parameter makes it a full channel.
 *
 * <p>States: {@code present} (3 of 3), {@code limited} (contained), {@code partial} (private data and a channel without
 * untrusted content), {@code none}; {@code dismissed} while the person's dismissal still names the same ingredients.
 */
@Component
public class RiskAnalyzer {

  /** The three ingredients; {@code externalChannel} is {@code present}, {@code limited} or {@code absent}. */
  public record Ingredients(boolean privateData, boolean untrustedContent, String externalChannel) {

    public int count() {
      return (privateData ? 1 : 0) + (untrustedContent ? 1 : 0) + ("absent".equals(externalChannel) ? 0 : 1);
    }

    public JsonNode toJson() {
      ObjectNode n = JsonNodeFactory.instance.objectNode();
      n.put("privateData", privateData ? "present" : "absent");
      n.put("untrustedContent", untrustedContent ? "present" : "absent");
      n.put("externalChannel", externalChannel);
      return n;
    }
  }

  /** Why an ingredient is present: {@code sql_query} reads {@code dev-db}. */
  public record Source(String ingredient, String tool, String connection, String text) {}

  /**
   * The callout of one endpoint.
   *
   * @param state     {@code present}, {@code limited}, {@code partial}, {@code none} or {@code dismissed}
   * @param published whether the endpoint is published through the tunnel ("…, published to the internet")
   * @param dismissed the person's dismissal ({@code {at, reason, ingredients}}) while it still applies, else null
   */
  public record Report(String state, Ingredients ingredients, List<Source> sources, boolean published,
      JsonNode dismissed) {

    /** The state without the dismissal: what the callout would say. */
    public String underlying() {
      return stateOf(ingredients);
    }
  }

  public Report analyze(EndpointSnapshot snapshot) {
    return analyze(snapshot.onPairs(), snapshot.published(), snapshot.riskDismissed());
  }

  /** The callout of a set of pairs: an endpoint, or one side of a split preview. */
  public Report analyze(List<EndpointSnapshot.Pair> pairs, boolean published, JsonNode dismissed) {
    boolean privateData = false;
    boolean untrusted = false;
    boolean channel = false;
    boolean limited = false;
    List<Source> sources = new ArrayList<>();
    for (EndpointSnapshot.Pair p : pairs) {
      if (!p.on()) {
        continue;
      }
      var risk = p.tool().risk();
      String tool = p.tool().name();
      String connection = p.connectionName();
      if (risk.privateData()) {
        privateData = true;
        sources.add(new Source("privateData", tool, connection, tool + " reads " + connection));
      }
      if (risk.untrustedContent()) {
        untrusted = true;
        sources.add(new Source("untrustedContent", tool, connection, tool + " returns text that others wrote on "
            + connection));
      }
      Egress egress = effectiveEgress(p);
      if (egress == Egress.CHANNEL) {
        channel = true;
        sources.add(new Source("externalChannel", tool, connection, tool + " can send data to " + connection));
      } else if (egress == Egress.LIMITED) {
        limited = true;
        sources.add(new Source("externalChannel", tool, connection, tool + " runs fixed runbooks on " + connection));
      }
    }
    Ingredients ingredients = new Ingredients(privateData, untrusted, channel ? "present" : limited ? "limited"
        : "absent");
    String state = stateOf(ingredients);
    JsonNode stillDismissed = null;
    if (dismissed != null && !dismissed.isNull() && !"none".equals(state)
        && Objects.equals(dismissed.path("ingredients"), ingredients.toJson())) {
      stillDismissed = dismissed;
      state = "dismissed";
    }
    return new Report(state, ingredients, List.copyOf(sources), published, stillDismissed);
  }

  static String stateOf(Ingredients i) {
    if (i.privateData() && i.untrustedContent() && "present".equals(i.externalChannel())) {
      return "present";
    }
    if (i.privateData() && i.untrustedContent() && "limited".equals(i.externalChannel())) {
      return "limited";
    }
    if (i.count() == 2 && i.privateData() && !"absent".equals(i.externalChannel())) {
      return "partial";
    }
    return "none";
  }

  /**
   * A runbook tool is a limited channel until one of its runbooks takes a free-text parameter: the SSH template kind
   * says so in its contribution ({@code {"freeText": true}}).
   */
  private static Egress effectiveEgress(EndpointSnapshot.Pair p) {
    Egress declared = p.tool().risk().egress();
    if (declared != Egress.LIMITED || p.contributions() == null) {
      return declared;
    }
    for (JsonNode contribution : p.contributions()) {
      if (contribution.path("freeText").asBoolean(false)) {
        return Egress.CHANNEL;
      }
    }
    return declared;
  }
}
