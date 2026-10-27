package ai.msrv.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.clients.internal.ClientAdapter;
import ai.msrv.gaterook.core.clients.internal.ClientAdapter.Entry;
import ai.msrv.gaterook.core.clients.internal.ClientAdapter.EntryValues;
import ai.msrv.gaterook.core.clients.internal.ClientEnvironment;
import ai.msrv.gaterook.core.clients.internal.JsonEntryEditor;
import ai.msrv.gaterook.core.clients.internal.persistence.ClientKind;
import ai.msrv.gaterook.core.clients.internal.persistence.LinkMethod;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Confirm of {@code method: token} (review K1-13): the token goes into the entry the preview showed, and a placeholder
 * the person's file already held — an "add it by hand" fragment pasted earlier — stays a placeholder.
 */
class ConnectTokenTest {

  @Test
  void theTokenGoesIntoOurEntryOnly() {
    String before = """
        {
          "mcpServers": {
            "pasted": {
              "url": "http://127.0.0.1:7465/mcp/dev",
              "headers": {
                "Authorization": "Bearer ‹new token›"
              }
            }
          }
        }
        """;
    ClientEnvironment env = new ClientEnvironment(ClientEnvironment.Os.LINUX, Path.of("/home/me"),
        Path.of("/home/me/.mcp-gateway"), null, Map.of(), true, "laptop");
    Entry entry = ClientAdapter.of(ClientKind.CURSOR).entry(env, LinkMethod.TOKEN, "gateway-dev", new EntryValues(
        "dev", "http://127.0.0.1:7465/mcp/dev", null, ClientAdapter.TOKEN_PLACEHOLDER, "writes", 60));
    String previewed = JsonEntryEditor.upsert(before, entry.rootKey(), entry.name(), entry.json());

    String written = ClientService.withToken(before, previewed, "gwt_the-token");

    assertThat(JsonEntryEditor.entry(written, "mcpServers", "gateway-dev").orElseThrow().path("headers")
        .path("Authorization").asString()).isEqualTo("Bearer gwt_the-token");
    assertThat(JsonEntryEditor.entry(written, "mcpServers", "pasted").orElseThrow().path("headers")
        .path("Authorization").asString()).isEqualTo("Bearer ‹new token›");
    assertThat(JsonEntryEditor.remove(written, "mcpServers", "gateway-dev")).isEqualTo(before);
  }
}
