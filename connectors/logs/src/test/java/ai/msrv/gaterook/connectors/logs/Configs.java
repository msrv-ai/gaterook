package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.util.Set;
import tools.jackson.databind.node.ObjectNode;

/** Connections and calls of the tests, written as the forms and agents write them. */
final class Configs {

  private Configs() {}

  static ConnectionConfig of(String name, Env env, String json, String... secretFields) {
    return new ConnectionConfig("c-" + name, name, "logs", env, 1, TestCtx.JSON.readTree(json), Set.of(secretFields),
        null);
  }

  static ConnectionConfig loki(String url) {
    return of("app-logs", Env.DEV, """
        {"backend":"loki","url":"%s","tenant":"team-a","auth":{"mode":"bearer"}}""".formatted(url), "/auth/secret");
  }

  static ConnectionConfig gcp() {
    return of("prod-logs", Env.PROD, """
        {"backend":"gcp-logging","resourceNames":["projects/acme-prod"],"scopeFilter":"resource.type=\\"k8s_container\\"",
         "credentials":{"source":"adc","impersonate":"gw-reader@acme-prod.iam.gserviceaccount.com"}}""");
  }

  static ConnectionConfig cloudWatch(boolean insights) {
    return of("aws-logs", Env.DEV, """
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/aws/ecs/api","/aws/ecs/worker"],
         "searchApi":"%s","credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}"""
        .formatted(insights ? "insights" : "filter"), "/credentials/secretAccessKey");
  }

  static ConnectionConfig openSearch(String url) {
    return of("search-logs", Env.DEV, """
        {"backend":"opensearch","apiAccess":"logs","url":"%s","indices":["logs-app-*"],
         "auth":{"mode":"basic","username":"gw_reader"},
         "labelFields":["service.name","host.name"]}""".formatted(url), "/auth/secret");
  }

  static ToolCall call(String tool, String arguments) {
    return new ToolCall("tc_logs_test", tool, "app-logs", (ObjectNode) TestCtx.JSON.readTree(arguments), null);
  }

  static PolicyView policy(Env env, Limits limits) {
    return new PolicyView(env, env.id() + ".logs", null, limits);
  }

  static PolicyView policy(Env env, Limits limits, String section) {
    return new PolicyView(env, env.id() + ".logs", TestCtx.JSON.readTree(section), limits);
  }
}
