package com.gaterook.connectors.mcpserver;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connectors.cloudauth.AwsSigV4;
import com.gaterook.connectors.cloudauth.AwsSpec;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.JsonNode;

/**
 * SigV4 on each request (ARCHITECTURE.md §7.6). MCP servers on AWS, such as the AWS MCP Server
 * ({@code https://aws-mcp.<region>.api.aws/mcp}) and Bedrock AgentCore, take requests signed with AWS credentials, as
 * the MCP Proxy for AWS signs them, in place of a browser sign-in that lasts at most 12 hours. The credentials are the
 * server's: an access key or a profile of this computer, optionally assuming a role with an ExternalId, through the
 * core's {@link CloudCredentials}. The service and region come from the URL's host unless the settings name them.
 */
final class AwsRequestCredentials implements RequestCredentials {

  /** Where the settings keep the credentials; the secret key is the secret field {@code …/secretAccessKey}. */
  static final String POINTER = "/auth/credentials";

  private final AwsCredentialsProvider provider;
  private final AwsSigV4.Target target;
  private final Clock clock;
  private final String server;
  private volatile AwsCredentials last;

  AwsRequestCredentials(AwsCredentialsProvider provider, AwsSigV4.Target target, Clock clock, String server) {
    this.provider = provider;
    this.target = target;
    this.clock = clock;
    this.server = server;
  }

  /**
   * The credentials of a stored or draft server: its {@code auth.credentials}, signing for {@link #target}.
   *
   * @throws ConnectorException {@code BAD_INPUT} for settings GateRook can't use, {@code RESOURCE_UNAVAILABLE} when the
   *                            profile can't be used
   */
  static AwsRequestCredentials of(CloudCredentials cloud, ConnectionConfig cfg, URI url, SecretResolver secrets,
      Consumer<CharSequence> taint, Clock clock) throws ConnectorException {
    AwsSigV4.Target target = target(cfg.config().path("auth"), url).orElseThrow(() -> ConnectorException.badInput(
        cfg.name() + ": name the AWS service and region to sign for. " + url.getHost() + " doesn't say."));
    AwsSpec spec;
    try {
      spec = AwsSpec.fromConfig(cfg.config(), POINTER, target.region());
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput(cfg.name() + ": " + e.getMessage());
    }
    AwsCredentialsProvider provider = cloud.aws(new CloudCredentials.Owner(cfg.id(), cfg.version(), cfg.name()), spec,
        null, secrets);
    return new AwsRequestCredentials(CloudCredentials.tainting(provider, taint), target, clock, cfg.name());
  }

  /**
   * The service and region to sign for: {@code service} and {@code region} of the sign-in settings, else what the
   * host names ({@code aws-mcp} and {@code us-east-1} for {@code aws-mcp.us-east-1.api.aws}). Empty when neither does.
   */
  static Optional<AwsSigV4.Target> target(JsonNode auth, URI url) {
    return AwsSigV4.target(Texts.str(auth.path("service"), null), Texts.str(auth.path("region"), null), url.getHost());
  }

  @Override
  public Map<String, String> headers(String method, URI url, byte[] body) throws ConnectorException {
    AwsCredentials c;
    try {
      c = provider.resolveCredentials();
    } catch (SdkException e) {
      throw ConnectorException.unavailable("GateRook has no AWS credentials for " + server + ": "
          + Texts.clean(String.valueOf(e.getMessage()), 300, false), e);
    }
    last = c;
    return AwsSigV4.sign(c, target, method, url, body, clock);
  }

  /** A signature is refused for what it is (wrong key, no permission, a clock off): signing again can't help. */
  @Override
  public boolean refused() {
    return false;
  }

  @Override
  public List<String> secrets() {
    AwsCredentials c = last;
    if (c == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>(List.of(c.secretAccessKey()));
    if (c instanceof AwsSessionCredentials session) {
      out.add(session.sessionToken());
    }
    return out;
  }

  @Override
  public String label() {
    return "AWS credentials";
  }
}
