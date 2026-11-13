package ai.msrv.gaterook.pluginhost;

/**
 * What the host calls the code it runs when it explains a refusal to a person or an agent: a plugin, or, for one of
 * GateRook's connector modules, a connector. One package runs in a host process, so the word is the process's.
 */
final class HostVoice {

  private static volatile boolean module;

  private HostVoice() {}

  /** Says whether the package this host loads is a connector module. */
  static void module(boolean value) {
    module = value;
  }

  /** {@code plugin}, or {@code connector} for a connector module. */
  static String noun() {
    return module ? "connector" : "plugin";
  }
}
