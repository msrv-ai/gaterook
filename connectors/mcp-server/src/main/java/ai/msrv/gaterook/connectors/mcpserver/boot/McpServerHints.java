package ai.msrv.gaterook.connectors.mcpserver.boot;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the External MCP server connector; they live with the module, not in the core
 * (ARCHITECTURE.md §2.3).
 *
 * <p>The client reads messages as trees, but a server's tools are bound to the SDK's {@code Tool} record with Jackson
 * for their definition hash ({@code Snapshots.tool}), and that record nests others ({@code ToolAnnotations},
 * {@code Icon}). One record missing from the image doesn't fail loudly: the tool can't be read and isn't published. So
 * every type nested in {@link McpSchema} is registered, recursively, with all its members.
 */
class McpServerHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool, connection and policy schemas are read with getResourceAsStream: null in native without this hint.
    hints.resources().registerPattern("connectors/mcp-server/*.schema.json");
    Deque<Class<?>> todo = new ArrayDeque<>();
    todo.add(McpSchema.class);
    while (!todo.isEmpty()) {
      Class<?> type = todo.removeFirst();
      hints.reflection().registerType(type, MemberCategory.values());
      for (Class<?> nested : type.getDeclaredClasses()) {
        todo.addLast(nested);
      }
    }
    // Anonymous classes aren't declared classes; McpSchema$1 is the TypeRef its message parser reads generics from.
    for (int i = 1; i <= 8; i++) {
      try {
        hints.reflection().registerType(Class.forName(McpSchema.class.getName() + "$" + i, false, classLoader),
            MemberCategory.values());
      } catch (ClassNotFoundException e) {
        break;
      }
    }
  }
}
