package ai.msrv.gaterook.core.connectors;

/**
 * A plugin was updated to a new generation (ARCHITECTURE.md §2.2 events): calls in flight finish on the old version,
 * new calls go to the new one. Published inside the transaction of the switch. Pending approval requests for the
 * plugin's tools were made against the old code, so the approvals module rejects them in that transaction ("Plugin
 * updated to {@code to}, ask again"). Public API of the module: the event carries what a listener needs, the
 * plugin's id being the id of its connector type.
 */
public record PluginGenerationSwitched(String pluginId, String fromVersion, String toVersion) {}
