package ai.msrv.gaterook.core.platform.web;

import ai.msrv.gaterook.core.platform.MasterKeyLock;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The locked mode on the local listener (ARCHITECTURE.md §7.1, case b). Runs after {@link LocalGuardFilter}, so every
 * request here already passed the guard and, for the admin API, the launch-secret signature. While
 * {@link MasterKeyLock} is set, only what the status screen needs answers: {@code GET /api/health},
 * {@code GET /api/events}, {@code GET /api/master-key}, {@code POST /api/master-key/restore|create} and
 * {@code POST /internal/files/grant} (the backup file the person picks). {@code /mcp/*} gets 503 with a JSON-RPC
 * {@code -32603} error, so a client shows why; everything else 503 problem+json.
 */
public final class LockedModeFilter extends OncePerRequestFilter {

  private static final Set<String> OPEN = Set.of("GET /api/health", "GET /api/events", "GET /api/master-key",
      "POST /api/master-key/restore", "POST /api/master-key/create", "POST /internal/files/grant");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final MasterKeyLock lock;

  public LockedModeFilter(MasterKeyLock lock) {
    this.lock = lock;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!lock.locked() || OPEN.contains(request.getMethod() + " " + request.getRequestURI())) {
      chain.doFilter(request, response);
      return;
    }
    String uri = request.getRequestURI();
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    String contentType;
    if (uri.startsWith("/mcp/")) {
      body.put("jsonrpc", "2.0");
      body.putNull("id");
      ObjectNode error = body.putObject("error");
      error.put("code", -32603);
      error.put("message", lock.message());
      contentType = "application/json";
    } else {
      body.put("type", "urn:gaterook:problem:locked");
      body.put("title", "GateRook is locked");
      body.put("detail", lock.message() + ". Restore the key from your backup or create a new one.");
      body.put("locked", lock.reason().map(Enum::name).orElse(null));
      contentType = "application/problem+json";
    }
    byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    response.setContentType(contentType);
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
  }
}
