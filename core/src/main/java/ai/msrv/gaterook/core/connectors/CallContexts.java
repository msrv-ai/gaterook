package ai.msrv.gaterook.core.connectors;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.Cidr;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.SealedHandles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link CallContext} of a call on one connection: guarded egress under the environment's address policy
 * (one pooled client per connection, dropped when the connection, its version or the policy's private ranges change),
 * the core's sealed handles, the shared remote quotas and the connector type's work folder
 * {@code ~/.gaterook/connectors/<type>/}.
 */
@Component
public class CallContexts {

  private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9-]{0,47}");

  private final EnvironmentPolicies policies;
  private final EgressClients egress;
  private final SealedHandles handles;
  private final GatewayPaths paths;
  private final Clock clock;
  private final RemoteQuotas quotas = new RemoteQuotas();
  private final Map<String, CachedSpec> specs = new ConcurrentHashMap<>();

  private record CachedSpec(String fingerprint, EgressSpec spec) {}

  public CallContexts(EnvironmentPolicies policies, EgressClients egress, SealedHandles handles, GatewayPaths paths,
      Clock clock) {
    this.policies = policies;
    this.egress = egress;
    this.handles = handles;
    this.paths = paths;
    this.clock = clock;
  }

  /** A context for one call on {@code cfg} with {@code limits}; the caller may still set trace, timeout, taint. */
  public CallContext.Builder builder(String callId, ConnectionConfig cfg, Limits limits) {
    return CallContext.builder(callId, cfg.env(), limits, clock)
        .handles(handles)
        .egress(egress.egress(spec(cfg)))
        .quotas(quotas)
        .workDir(workDir(cfg.type()))
        .connectorType(cfg.type());
  }

  /** Drops the pooled sockets of a connection that changed or was removed. */
  public void evict(String connectionId) {
    specs.remove(connectionId);
    egress.evict(poolKey(connectionId));
  }

  private EgressSpec spec(ConnectionConfig cfg) {
    List<String> ranges = policies.allowedPrivateRanges(cfg.env(), cfg.name());
    String fingerprint = cfg.version() + "|" + String.join(",", ranges);
    CachedSpec cached = specs.get(cfg.id());
    if (cached != null && cached.fingerprint().equals(fingerprint)) {
      return cached.spec();
    }
    if (cached != null) {
      // A removed range must not live on in a pooled socket.
      egress.evict(poolKey(cfg.id()));
    }
    List<Cidr> cidrs = new ArrayList<>();
    for (String range : ranges) {
      cidrs.add(Cidr.parse(range));
    }
    AddressPolicy policy = AddressPolicy.publicOnly(egress.settings().corePorts().get()).allowing(cidrs);
    EgressSpec spec = EgressSpec.of(poolKey(cfg.id()), policy);
    specs.put(cfg.id(), new CachedSpec(fingerprint, spec));
    return spec;
  }

  private Path workDir(String type) {
    if (!TYPE.matcher(type).matches()) {
      throw new IllegalArgumentException("Invalid connector type " + type);
    }
    Path dir = paths.home().resolve("connectors").resolve(type);
    try {
      if (!Files.isDirectory(dir)) {
        GatewayPaths.ownerOnlyDirectory(dir.getParent());
        GatewayPaths.ownerOnlyDirectory(dir);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return dir;
  }

  private static String poolKey(String connectionId) {
    return "connection:" + connectionId;
  }
}
