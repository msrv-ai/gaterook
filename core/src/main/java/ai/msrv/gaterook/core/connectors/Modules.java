package ai.msrv.gaterook.core.connectors;

import ai.msrv.gaterook.core.connectors.plugins.ModuleChannel;
import ai.msrv.gaterook.core.connectors.plugins.ModuleService;
import ai.msrv.gaterook.core.connectors.plugins.PluginCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The public face of GateRook's connector modules for the rest of the core (ARCHITECTURE.md §2.2,
 * docs/connector-delivery.md): the connectors that aren't part of the installer and arrive when something needs
 * them. The admin API lists them and follows an installation; whatever is about to use a connector type that isn't
 * here — Add connection, an import, a restored backup's first call — asks {@link #ensure} or {@link #await} and
 * never asks the person anything.
 */
@Component
public class Modules {

  /**
   * A connector module.
   *
   * @param state     {@code ready}, {@code installing}, {@code missing}, {@code available}, {@code failed} or
   *                  {@code disabled}
   * @param step      the step of a running installation ({@code runtime}, {@code downloading}, {@code verifying},
   *                  {@code installing}), or {@code null}
   * @param sizeBytes the size of its package
   * @param message   why the last installation failed, or {@code null}
   */
  public record Module(String id, String name, String version, String state, String step, long sizeBytes,
      String message, List<Type> types, boolean installed) {

    public boolean ready() {
      return "ready".equals(state);
    }
  }

  /** A connector type a module adds, as a card shows it before the module is installed. */
  public record Type(String id, String name, String icon, String group, String description, int tools) {}

  /**
   * The module channel's state.
   *
   * @param configured whether this build has a channel at all
   * @param problem    why the last check failed or why nothing installs from it, or {@code null}
   */
  public record Channel(boolean configured, Instant checkedAt, boolean offline, String problem) {}

  private final ModuleService service;

  Modules(ModuleService service) {
    this.service = service;
  }

  /** Every module, installed or listed. Starts no download; the channel is checked when the last check is old. */
  public List<Module> list() {
    if (service.channel().configured()) {
      service.channel().state();
    }
    return service.list().stream().map(Modules::module).toList();
  }

  /** The module of that id, or the one that adds the connector type of that id, as it stands now. */
  public Optional<Module> find(String typeOrModuleId) {
    return service.statusOf(typeOrModuleId).map(Modules::module);
  }

  /** Whether a module adds the connector type: one installed here or one the channel lists. */
  public boolean delivers(String typeId) {
    return service.delivers(typeId);
  }

  /**
   * Starts the installation of the module of that id (or of the one that adds that connector type) unless it is
   * installed or installing, and says where it stands. 404 when no module is or adds it.
   */
  public Module ensure(String typeOrModuleId) {
    return module(service.ensure(typeOrModuleId));
  }

  /** {@link #ensure}, then waits up to {@code budget} for the module to be ready. */
  public Module await(String typeOrModuleId, Duration budget) {
    return module(service.ensureAndWait(typeOrModuleId, budget));
  }

  public void uninstall(String moduleId, String reason) {
    service.uninstall(moduleId, reason);
  }

  public Channel channel() {
    return channel(service.channel().configured() ? service.channel().state() : service.channel().cached());
  }

  public Channel refreshChannel() {
    return channel(service.channel().refresh());
  }

  private Channel channel(PluginCatalog.State state) {
    return new Channel(service.channel().configured(), state.checkedAt(), state.offline(),
        service.channel().configured() ? state.problem() : null);
  }

  private static Module module(ModuleService.Status s) {
    List<Type> types = s.types().stream().map(Modules::type).toList();
    return new Module(s.id(), s.name(), s.version(), s.state(), s.step(), s.sizeBytes(), s.message(), types,
        s.installed());
  }

  private static Type type(ModuleChannel.TypeInfo t) {
    return new Type(t.id(), t.name(), t.icon(), t.group(), t.description(), t.tools().size());
  }
}
