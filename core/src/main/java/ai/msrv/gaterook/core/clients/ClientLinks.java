package ai.msrv.gaterook.core.clients;

import ai.msrv.gaterook.core.endpoints.EndpointRemovalParticipant;
import ai.msrv.gaterook.core.platform.SetupProgress;
import org.springframework.stereotype.Component;

/**
 * Where the clients module takes part in the rest of the core: an endpoint's deletion disconnects the clients linked to
 * it and says which ones and what is left to do in them; Overview's "Connect a client" step and Clients node count what
 * is connected.
 */
@Component
class ClientLinks implements EndpointRemovalParticipant, SetupProgress {

  private final ClientService clients;

  ClientLinks(ClientService clients) {
    this.clients = clients;
  }

  @Override
  public Removal onEndpointRemoving(String endpointId, String slug, String reason) {
    ClientService.EndpointRemoval r = clients.endpointRemoving(endpointId, slug, reason);
    return new Removal(r.clients(), 0, r.notes());
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
