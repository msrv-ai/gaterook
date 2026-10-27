/**
 * MCP module (ARCHITECTURE.md §2.2). Transport {@code /mcp/{slug}}, EraResolver, McpDispatcher, the protocol types,
 * core tools, resources and prompts, ApprovalDelivery (MRTR or handle), RequestStateCodec (key from
 * platform.HmacKeys), ResultWriter, OTel spans and the {@code /approvals/{id}} page.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "MCP", allowedDependencies = {
        "platform", "endpoints", "policy", "approvals", "invocation", "connectors", "connections", "audit"})
package ai.msrv.gaterook.core.mcp;

import org.springframework.modulith.ApplicationModule;
