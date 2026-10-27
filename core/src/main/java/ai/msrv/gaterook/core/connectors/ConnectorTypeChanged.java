package ai.msrv.gaterook.core.connectors;

/**
 * A connector type was switched on or off, its manifest changed (upgrade of the gateway or of a plugin) or its row was
 * confirmed after a review. Endpoint snapshots that publish its tools are rebuilt. Published inside the transaction of
 * the change (ARCHITECTURE.md §2.2).
 */
public record ConnectorTypeChanged(String typeId) {}
