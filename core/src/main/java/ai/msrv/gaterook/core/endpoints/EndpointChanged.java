package ai.msrv.gaterook.core.endpoints;

/**
 * An endpoint was created or changed: settings, access, connections, tool modes, tokens excepted. Its snapshot is
 * rebuilt. Published inside the transaction of the change.
 */
public record EndpointChanged(String endpointId, String slug) {}
