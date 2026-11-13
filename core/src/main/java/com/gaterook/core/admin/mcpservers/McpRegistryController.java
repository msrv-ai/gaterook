package com.gaterook.core.admin.mcpservers;

import com.gaterook.core.admin.ApiProblem;
import com.gaterook.core.connectors.McpRegistry;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search of an MCP registry for Add MCP server (ARCHITECTURE.md §7.5): one page of servers whose names match, the
 * latest version of each, as {@link McpRegistry} checked them. Choosing one only fills the form; adding still goes
 * through {@code POST /api/mcp-servers/review}.
 */
@RestController
@RegisterReflectionForBinding({McpRegistry.Page.class, McpRegistry.Server.class, McpRegistry.Remote.class,
    McpRegistry.Package.class, McpRegistry.Input.class, McpRegistry.Argument.class, McpRegistry.Listing.class})
class McpRegistryController {

  private final McpRegistry registry;

  McpRegistryController(McpRegistry registry) {
    this.registry = registry;
  }

  @GetMapping("/api/mcp-registry/servers")
  McpRegistry.Page search(@RequestParam(name = "search", required = false) String search,
      @RequestParam(name = "cursor", required = false) String cursor) {
    return registry.search(search, cursor);
  }

  /** The core reached for the registry but got no usable answer: what happened, as a 502. */
  @ExceptionHandler(McpRegistry.UnavailableException.class)
  ResponseEntity<ApiProblem> unavailable(McpRegistry.UnavailableException e) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ApiProblem.of("The registry didn't answer", e.getMessage()));
  }
}
