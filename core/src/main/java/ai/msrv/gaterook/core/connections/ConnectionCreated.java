package ai.msrv.gaterook.core.connections;

/** Published inside the creation transaction so the first health check starts only after a successful commit. */
public record ConnectionCreated(String connectionId) {}
