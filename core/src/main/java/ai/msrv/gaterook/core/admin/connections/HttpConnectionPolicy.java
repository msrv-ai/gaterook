package ai.msrv.gaterook.core.admin.connections;

import ai.msrv.gaterook.core.connections.ConnectionConfigured;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.util.LinkedHashSet;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Adds saved HTTP base URLs to the same environment's egress policy in the connection's transaction. */
@Component
class HttpConnectionPolicy {

  private final PolicyService policies;

  HttpConnectionPolicy(PolicyService policies) {
    this.policies = policies;
  }

  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void onConnectionConfigured(ConnectionConfigured event) {
    if (!"http".equals(event.type())) {
      return;
    }
    PolicyService.EnvPolicy current = policies.get(event.env());
    LinkedHashSet<String> urls = new LinkedHashSet<>();
    current.sections().path("http").path("allowedBaseUrls").forEach(url -> urls.add(url.asString()));
    boolean changed = false;
    for (JsonNode url : event.config().path("baseUrls")) {
      changed |= urls.add(url.asString());
    }
    if (!changed) {
      return;
    }
    ObjectNode sections = JsonNodeFactory.instance.objectNode();
    var allowed = sections.putObject("http").putArray("allowedBaseUrls");
    urls.forEach(allowed::add);
    String reason = event.reason() == null || event.reason().isBlank()
        ? "Allow base URLs for HTTP connection " + event.name() : event.reason();
    policies.update(event.env(), sections, current.version(), reason, null);
  }
}
