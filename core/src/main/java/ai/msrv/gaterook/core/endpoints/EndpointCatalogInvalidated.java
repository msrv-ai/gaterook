package ai.msrv.gaterook.core.endpoints;

/**
 * Endpoint snapshots were evicted after a committed change, so their published tool catalogs may have changed.
 * Consumers compare the new catalog with their last view before notifying clients. Reads should run outside the
 * committing thread because its transaction resources may still be bound while after-commit listeners run.
 *
 * @param endpointId the affected endpoint, or {@code null} when any endpoint may be affected
 */
public record EndpointCatalogInvalidated(String endpointId) {}
