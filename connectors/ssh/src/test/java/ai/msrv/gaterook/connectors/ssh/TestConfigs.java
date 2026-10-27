package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** SSH connection configs and endpoint views as the core would hand them to the connector. */
final class TestConfigs {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private TestConfigs() {}

  static JsonNode json(String text) {
    return JSON.readTree(text);
  }

  /** {@code deploy@127.0.0.1:<port>} with a password, its host key pinned when {@code pinned} isn't null. */
  static ObjectNode password(int port, String pinned) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.putObject("connection").put("host", "127.0.0.1").put("port", port).put("username", TestSshd.USER);
    c.putObject("credentials").put("method", "password");
    if (pinned != null) {
      c.putObject("hostKeys").putArray("pinned").add(pinned);
    }
    return c;
  }

  static ObjectNode with(ObjectNode config, Consumer<ObjectNode> change) {
    ObjectNode copy = config.deepCopy();
    change.accept(copy);
    return copy;
  }

  static ConnectionConfig connection(String id, String name, Env env, long version, JsonNode config) {
    return new ConnectionConfig(id, name, SshConnector.TYPE, env, version, config,
        Set.of("/credentials/password", "/credentials/privateKey", "/credentials/passphrase",
            "/jump/credentials/password", "/jump/credentials/privateKey", "/jump/credentials/passphrase"),
        JsonNodeFactory.instance.objectNode());
  }

  /** A runbook definition as the editor saves it, read only confirmed when {@code readOnly}. */
  static ObjectNode runbook(String name, String command, boolean readOnly, String paramsJson) {
    ObjectNode d = JsonNodeFactory.instance.objectNode();
    d.put("name", name);
    d.put("command", command);
    if (readOnly) {
      d.put("readOnly", true);
      d.put("readOnlyConfirmedFor", command);
    }
    if (paramsJson != null) {
      d.set("params", json(paramsJson));
    }
    return d;
  }

  /** The contributions of compiled runbook templates, as {@code ToolPairs} hands them over. */
  static List<EndpointView.Contribution> contributions(String connectionId, ObjectNode... definitions) {
    List<EndpointView.Contribution> out = new ArrayList<>();
    int i = 0;
    for (ObjectNode d : definitions) {
      RunbookCompiler.Outcome o = RunbookCompiler.compile(d);
      if (!o.ok()) {
        throw new IllegalArgumentException(String.join("; ", o.errors()));
      }
      out.add(new EndpointView.Contribution(connectionId, "tpl_" + d.path("name").asString() + "_" + i++, 1,
          o.runbook().toJson()));
    }
    return out;
  }

  /**
   * The policy of {@code cfg}'s environment with {@code maxConcurrentCalls = n}, read the way the core does before
   * every call: by classifying one ({@code execute} gets no policy).
   */
  static void allowParallel(SshConnector connector, ConnectionConfig cfg, int n) {
    ObjectNode section = JsonNodeFactory.instance.objectNode().put("maxConcurrentCalls", n);
    connector.classify(new ai.msrv.gaterook.connector.api.ToolCall("tc_policy", SshTools.RUN, cfg.name(),
        JsonNodeFactory.instance.objectNode().put("runbook", "-"), null), cfg,
        new ai.msrv.gaterook.connector.api.PolicyView(cfg.env(), cfg.env().id() + ".ssh", section, null));
  }

  /** Offers the runbooks to the connector the way the core does: by building the endpoint's tools. */
  static void offer(SshConnector connector, Limits limits, List<ConnectionConfig> connections,
      List<EndpointView.Contribution> contributions) {
    Map<String, Limits> byConnection = new LinkedHashMap<>();
    connections.forEach(c -> byConnection.put(c.id(), limits));
    connector.tools(new EndpointView("dev-tools", Env.DEV, connections, byConnection, contributions));
  }
}
