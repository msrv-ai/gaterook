package com.gaterook.connectors.azure;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.ToolCall;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.node.ObjectNode;

/** Connections, calls and policies of the tests, written as the forms, agents and the core write them. */
final class Configs {

  private static final AtomicInteger CALLS = new AtomicInteger();

  private Configs() {}

  static ConnectionConfig of(String name, Env env, String json, String... secretFields) {
    return new ConnectionConfig("c-" + name, name, "azure", env, 1, TestCtx.JSON.readTree(json), Set.of(secretFields),
        null);
  }

  /** {@code azure-stage}: a service principal with a client secret in the fake tenant. */
  static ConnectionConfig stage() {
    return of("azure-stage", Env.STAGE, """
        {"tenantId":"%s","subscriptions":["%s"],"credentials":{"source":"clientSecret","clientId":"%s"},
         "logAnalyticsWorkspaces":["5b2f8c1e-0a3d-4e6f-9b7a-1c2d3e4f5a6b"]}""".formatted(FakeAzure.TENANT,
        FakeAzure.SUB, FakeAzure.CLIENT), "/credentials/clientSecret");
  }

  static ConnectionConfig prod() {
    return of("azure-prod", Env.PROD, """
        {"tenantId":"%s","subscriptions":["%s"],"credentials":{"source":"clientSecret","clientId":"%s"}}"""
        .formatted(FakeAzure.TENANT, FakeAzure.SUB, FakeAzure.CLIENT), "/credentials/clientSecret");
  }

  static TestSecrets secrets() {
    return new TestSecrets().with("/credentials/clientSecret", "cnry-azure-client-secret-0001");
  }

  /** A call with its own id, as the core makes one per tools/call. */
  static ToolCall call(String tool, String connection, String arguments) {
    return new ToolCall("tc_azure_" + CALLS.incrementAndGet(), tool, connection,
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
