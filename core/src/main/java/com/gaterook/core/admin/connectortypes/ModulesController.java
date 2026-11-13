package com.gaterook.core.admin.connectortypes;

import com.gaterook.core.connectors.Modules;
import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * GateRook's connector modules (docs/connector-delivery.md): the connectors that aren't part of the installer. The
 * UI lists them, starts an installation when a person chooses one of their connector types and follows it by the
 * {@code module.state} events; nothing here asks for a review or a confirmation. A module's package that a person
 * chooses as a file is checked and installed where a plugin's is ({@code POST /api/plugins/check} and
 * {@code /api/plugins/install}), with a review.
 */
@RestController
@RegisterReflectionForBinding({ModulesController.ModulesView.class, ModulesController.Reason.class,
    Modules.Module.class, Modules.Type.class, Modules.Channel.class})
class ModulesController {

  /** {@code channel.problem}: why the list couldn't be checked or why nothing installs from it, else {@code null}. */
  record ModulesView(Modules.Channel channel, List<Modules.Module> modules) {}

  record Reason(String reason) {}

  private final Modules modules;

  ModulesController(Modules modules) {
    this.modules = modules;
  }

  @GetMapping("/api/modules")
  ModulesView list() {
    List<Modules.Module> all = modules.list();
    return new ModulesView(modules.channel(), all);
  }

  /** Checks the list of modules again now and starts the updates it calls for. */
  @PostMapping("/api/modules/refresh")
  ModulesView refresh() {
    Modules.Channel channel = modules.refreshChannel();
    return new ModulesView(channel, modules.list());
  }

  /**
   * Starts the installation of a module (by its id, or by the id of a connector type it adds) unless it is installed
   * or installing, and answers at once with where it stands.
   */
  @PostMapping("/api/modules/{id}/install")
  Modules.Module install(@PathVariable String id) {
    return modules.ensure(id);
  }

  /**
   * Returns an installed module to the version it had before, which is kept for a week. An update doesn't install
   * the version it was rolled back from again. 409 when no previous version is kept.
   */
  @PostMapping("/api/modules/{id}/rollback")
  Modules.Module rollback(@PathVariable String id, @RequestBody(required = false) Reason reason) {
    return modules.rollback(id, reason == null ? null : reason.reason());
  }

  /**
   * Replaces a person's own build of a module, installed from a file, with the one GateRook's list has: the way back
   * to GateRook's connector while connections use the module's types. Answers at once with where the installation
   * stands. 409 when the list doesn't have the module.
   */
  @PostMapping("/api/modules/{id}/replace-own")
  Modules.Module replaceOwn(@PathVariable String id) {
    return modules.replaceOwn(id);
  }

  /** Removes an installed module. 409 while connections use one of its connector types. */
  @DeleteMapping("/api/modules/{id}")
  void uninstall(@PathVariable String id, @RequestBody(required = false) Reason reason) {
    modules.uninstall(id, reason == null ? null : reason.reason());
  }
}
