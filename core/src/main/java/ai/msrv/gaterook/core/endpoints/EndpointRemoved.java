package ai.msrv.gaterook.core.endpoints;

/** An endpoint was deleted. Published inside the deletion's transaction. */
public record EndpointRemoved(String endpointId, String slug) {}
