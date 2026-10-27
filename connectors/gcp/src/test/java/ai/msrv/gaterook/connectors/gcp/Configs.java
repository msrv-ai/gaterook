package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.node.ObjectNode;

/** Connections, calls and policies of the tests, written as the forms, agents and the core write them. */
final class Configs {

  private static final AtomicInteger CALLS = new AtomicInteger();

  private Configs() {}

  static ConnectionConfig of(String name, Env env, String json, String... secretFields) {
    return new ConnectionConfig("c-" + name, name, "gcp", env, 1, TestCtx.JSON.readTree(json), Set.of(secretFields),
        null);
  }

  /** {@code gcp-stage}: Google sign-in on this computer impersonating {@link FakeGoogle#SA}. */
  static ConnectionConfig stage() {
    return of("gcp-stage", Env.STAGE, """
        {"scopes":["projects/acme-stage","folders/123456"],"metricsProjects":["acme-stage"],
         "credentials":{"source":"adc","impersonate":"%s"}}""".formatted(FakeGoogle.SA));
  }

  static ConnectionConfig prod() {
    return of("gcp-prod", Env.PROD, """
        {"scopes":["projects/acme-prod"],"credentials":{"source":"adc","impersonate":"gw-reader@acme-prod.iam.gserviceaccount.com"}}""");
  }

  /** A call with its own id, as the core makes one per tools/call. */
  static ToolCall call(String tool, String connection, String arguments) {
    return new ToolCall("tc_gcp_" + CALLS.incrementAndGet(), tool, connection,
        (ObjectNode) TestCtx.JSON.readTree(arguments), null);
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".cloud", null, limits(env));
  }

  static PolicyView policy(Env env, String section) {
    return new PolicyView(env, env.id() + ".cloud", TestCtx.JSON.readTree(section), limits(env));
  }

  static Limits limits(Env env) {
    return env == Env.PROD ? TestCtx.prod() : TestCtx.dev();
  }
}
