package ai.msrv.gaterook.core.connectors;

/**
 * A plugin was updated to a new generation (ARCHITECTURE.md §2.2 events): calls in flight finish on the old version,
 * new calls go to the new one. Published inside the transaction of the switch. Pending approval requests for the
 * plugin's tools were made against the old code, so the approvals module rejects them in that transaction ("Plugin
 * updated to {@code to}, ask again"). Public API of the module: the event carries what a listener needs, the
 * plugin's id being the id of its connector type.
 */
public record PluginGenerationSwitched(String pluginId, String fromVersion, String toVersion, boolean module) {

  /** A plugin's switch. */
  public PluginGenerationSwitched(String pluginId, String fromVersion, String toVersion) {
    this(pluginId, fromVersion, toVersion, false);
  }

  /**
   * One connector type of a connector module switched with its module: {@code pluginId} is the type's id, as it is a
   * plugin's.
   */
  public static PluginGenerationSwitched ofModuleType(String typeId, String fromVersion, String toVersion) {
    return new PluginGenerationSwitched(typeId, fromVersion, toVersion, true);
  }
}
