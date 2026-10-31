package ai.msrv.gaterook.core.connections;

import ai.msrv.gaterook.connector.api.Env;
import tools.jackson.databind.JsonNode;

/**
 * Published synchronously inside a successful create or edit transaction, after configuration validation.
 * Listeners may join that transaction; a failure rolls back the save. Health, metadata, secret rotation and policy
 * invalidation do not publish this event.
 */
public record ConnectionConfigured(String name, String type, Env env, JsonNode config, String reason) {}
