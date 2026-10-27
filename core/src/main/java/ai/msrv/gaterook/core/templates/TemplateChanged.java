package ai.msrv.gaterook.core.templates;

/**
 * A template got a new version, was created, disabled, invalidated or deleted. Endpoints that wire its connection
 * reconcile their tool pairs (new tools Off after the first version, Read only → Approval when a tool starts changing
 * data). Published inside the transaction of the change.
 *
 * @param connectionId its Runs on connection, or {@code null} once that was deleted
 */
public record TemplateChanged(String templateId, String connectionId) {}
