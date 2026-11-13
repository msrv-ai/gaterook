package com.gaterook.connectors.s3;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection and the health check of an S3 connection. On Amazon S3 the first check is the identity AWS sees
 * ({@code sts:GetCallerIdentity}, the proof that a role really was assumed); then the buckets agents may use, from
 * {@code ListBuckets}. Credentials scoped to some buckets often may not list buckets at all: the buckets the
 * connection names are then checked one by one with {@code HeadBucket}.
 */
final class ConnectionTest {

  /** Named buckets checked one by one when the credentials may not list buckets. */
  static final int MAX_HEADS = 5;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final S3Api api;
  private final ConnectionConfig cfg;
  private final SecretResolver secrets;
  private final ExecContext ctx;

  ConnectionTest(S3Api api, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    this.api = api;
    this.cfg = cfg;
    this.secrets = secrets;
    this.ctx = ctx;
  }

  private record Visible(int count, List<String> first, boolean more, String how) {}

  TestResult run() {
    long started = System.nanoTime();
    S3Settings s;
    try {
      s = S3Settings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("s3.settings", e.getMessage(), null);
    }
    S3Api.Call c = new S3Api.Call(cfg, s, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    String identity = s.identity();
    String account = null;
    if (s.aws()) {
      try {
        CloudCredentials.CallerIdentity id = api.identity(c, SessionPolicies.read(s, S3Tools.LIST_BUCKETS));
        identity = id.arn();
        account = id.account();
      } catch (ConnectorException e) {
        return TestResult.failure("s3.credentials", e.getMessage(), null);
      }
      checks.add(new TestResult.Check("identity", true, ms(started), identity));
    }
    long listed = System.nanoTime();
    Visible visible;
    try {
      visible = list(c);
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.RESOURCE_ERROR && named(s).size() > 0) {
        try {
          visible = heads(c, named(s));
        } catch (ConnectorException again) {
          return failure(again, checks);
        }
      } else {
        return failure(e, checks);
      }
    }
    long latency = ms(listed);
    checks.add(new TestResult.Check("buckets", true, latency, visible.count() + (visible.count() == 1 ? " bucket"
        : " buckets") + " agents may use" + (visible.first().isEmpty() ? "" : ": " + String.join(", ",
        visible.first()) + (visible.more() ? ", …" : "")) + visible.how()));
    ObjectNode details = F.objectNode().put("sessionPolicy", !s.aws() ? "not available"
        : s.generatedPolicy() ? "generated per call" : "none");
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN,
        "Bucket and object listings, metadata and text reads", null, List.of(), details);
    ObjectNode metadata = F.objectNode().put("serverVersion", s.aws() ? "Amazon S3 " + s.region() : "S3 · "
        + s.target()).put("region", s.region());
    if (account != null) {
      metadata.put("account", account);
    }
    if (!s.aws()) {
      metadata.put("endpoint", s.endpoint().toString());
    }
    TestResult result = TestResult.success(latency, s.aws() ? "Amazon S3 · " + s.region() : "S3 · " + s.target(),
        identity, permissions).withChecks(checks).withMetadata(metadata);
    for (TestResult.Hint h : S3Connector.hints(cfg.env(), s, visible.count(), true)) {
      result = result.withHint(h);
    }
    return result;
  }

  private TestResult failure(ConnectorException e, List<TestResult.Check> checks) {
    return TestResult.failure(e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE
        || e.kind() == ConnectorException.Kind.TIMEOUT ? "s3.unreachable" : "s3.buckets", e.getMessage(), null)
        .withChecks(checks);
  }

  /** The bucket names of the connection's list without patterns. */
  private static List<String> named(S3Settings s) {
    List<String> out = new ArrayList<>();
    for (String b : s.buckets()) {
      if (b.indexOf('*') < 0 && Names.bucketProblem(b, s.aws()) == null) {
        out.add(b);
      }
    }
    return out;
  }

  /** One page of {@code ListBuckets}: the buckets on the connection's list. */
  private Visible list(S3Api.Call c) throws ConnectorException {
    S3Settings s = c.settings();
    S3Request r = S3Request.of("ListBuckets", "GET", null, null);
    if (s.aws()) {
      r = r.param("max-buckets", "1000").param("bucket-region", s.region());
    }
    S3Api.Answer a = api.send(c, r, SessionPolicies.read(s, S3Tools.LIST_BUCKETS), Reads.MAX_LIST);
    if (a.status() != 200) {
      throw S3Api.error(a, r, c, false);
    }
    Element root;
    try {
      root = S3Xml.parse(a.body());
    } catch (IOException e) {
      throw ConnectorException.resourceError("The answer to ListBuckets isn't S3's XML.", null);
    }
    int count = 0;
    List<String> first = new ArrayList<>();
    Element buckets = S3Xml.child(root, "Buckets");
    for (Element b : buckets == null ? List.<Element>of() : S3Xml.children(buckets, "Bucket")) {
      String name = S3Xml.text(b, "Name");
      if (name != null && s.allows(name)) {
        count++;
        if (first.size() < 5) {
          first.add(name);
        }
      }
    }
    String token = S3Xml.text(root, "ContinuationToken");
    return new Visible(count, first, count > first.size() || token != null && !token.isBlank(), "");
  }

  /** {@code HeadBucket} of each named bucket, up to {@link #MAX_HEADS}: the ones that answer 200. */
  private Visible heads(S3Api.Call c, List<String> names) throws ConnectorException {
    int count = 0;
    List<String> first = new ArrayList<>();
    ConnectorException last = null;
    for (String name : names.subList(0, Math.min(MAX_HEADS, names.size()))) {
      S3Request r = S3Request.of("HeadBucket", "HEAD", name, null);
      S3Api.Answer a = api.send(c, r, SessionPolicies.read(c.settings(), S3Tools.LIST_OBJECTS), 0);
      if (a.status() == 200) {
        count++;
        first.add(name);
      } else {
        last = S3Api.error(a, r, c, false);
      }
    }
    if (count == 0 && last != null) {
      throw last;
    }
    return new Visible(count, first, names.size() > MAX_HEADS, " (the credentials may not list buckets, so the "
        + "named ones were checked)");
  }

  /** {@code ListBuckets} answered, or refused for want of permission: S3 is there and took the signature. */
  HealthResult health() throws ConnectorException {
    S3Settings s = S3Settings.of(cfg);
    long started = System.nanoTime();
    S3Api.Call c = new S3Api.Call(cfg, s, secrets, ctx);
    S3Request r = S3Request.of("ListBuckets", "GET", null, null);
    if (s.aws()) {
      r = r.param("max-buckets", "1").param("bucket-region", s.region());
    }
    S3Api.Answer a = api.send(c, r, SessionPolicies.read(s, S3Tools.LIST_BUCKETS), Reads.MAX_LIST);
    if (a.status() == 200 || a.status() == 403 && "AccessDenied".equals(S3Api.parseError(a).code())) {
      return HealthResult.healthy(ms(started));
    }
    return HealthResult.unreachable(S3Api.error(a, r, c, false).getMessage());
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
