package ai.msrv.gaterook.core.clients;

import ai.msrv.gaterook.core.endpoints.EndpointRemovalParticipant;
import ai.msrv.gaterook.core.platform.SetupProgress;
import org.springframework.stereotype.Component;

/**
 * Where the clients module takes part in the rest of the core: an endpoint's deletion ends its client links and says
 * which clients stop working; Overview's "Connect a client" step and Clients node count what is connected.
 */
@Component
class ClientLinks implements EndpointRemovalParticipant, SetupProgress {

  private final ClientService clients;

  ClientLinks(ClientService clients) {
    this.clients = clients;
  }

  @Override
  public Removal onEndpointRemoving(String endpointId, String slug, String reason) {
    return new Removal(clients.endpointRemoving(endpointId, slug), 0);
  }

  @Override
  public String area() {
    return "clients";
  }

  @Override
  public Progress progress() {
    return clients.progress();
  }
}
