package ai.msrv.gaterook.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code connectors/delivery.json} as the tests of the assembly read it: which connector types are compiled into the
 * core ({@code bundled}), which are delivered as modules, and which are modules that this release still compiles in
 * ({@code bridge}). The default classpath of {@code app} holds the bundled and the bridge ones; the
 * {@code all-bundled} profile adds the others, and a test that lists every type of the running core then expects
 * them too ({@link #inProcess}). A build with {@code -Dgateway.withoutBridge} lacks the bridge ones
 * ({@link #bridged}).
 */
public final class ConnectorDelivery {

  private static final JsonNode MAP = read();

  private ConnectorDelivery() {}

  /** The types of the connectors with that delivery: {@code bundled}, {@code module} or {@code bridge}. */
  public static Set<String> types(String delivery) {
    Set<String> out = new LinkedHashSet<>();
    for (Map.Entry<String, JsonNode> connector : MAP.path("connectors").properties()) {
      if (delivery.equals(connector.getValue().path("delivery").asString())) {
        connector.getValue().path("types").forEach(type -> out.add(type.asString()));
      }
    }
    return out;
  }

  /** Whether this run has the module-delivered connectors on its classpath (the {@code all-bundled} profile). */
  public static boolean inProcess() {
    try {
      Class.forName("ai.msrv.gaterook.connectors.cassandra.CassandraConnector", false,
          ConnectorDelivery.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /** Whether this run has the bridge connectors on its classpath (the default; not with -Dgateway.withoutBridge). */
  public static boolean bridged() {
    try {
      Class.forName("ai.msrv.gaterook.connectors.sql.boot.OracleConnectorAutoConfiguration", false,
          ConnectorDelivery.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /**
   * Every connector type the assembled core of this run has: the bundled ones, the bridge ones unless the run is
   * without them, and the module-delivered ones when in process.
   */
  public static Set<String> assembled() {
    Set<String> out = new LinkedHashSet<>(types("bundled"));
    if (bridged()) {
      out.addAll(types("bridge"));
    }
    if (inProcess()) {
      out.addAll(types("module"));
    }
    return out;
  }

  /** The types that connectors/delivery.json knows and that this run's core doesn't have. */
  private static Set<String> absent() {
    Set<String> out = new LinkedHashSet<>();
    if (!inProcess()) {
      out.addAll(types("module"));
    }
    if (!bridged()) {
      out.addAll(types("bridge"));
    }
    return out;
  }

  /** {@code ids} without the types this run doesn't have, in their order. */
  public static List<String> present(List<String> ids) {
    List<String> out = new ArrayList<>(ids);
    out.removeAll(absent());
    return out;
  }

  /** Whether {@code type} is in the assembled core of this run. */
  public static boolean has(String type) {
    return !absent().contains(type);
  }

  private static JsonNode read() {
    // Surefire and Failsafe run in the module's directory; an IDE may run in the repository root.
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path file = dir.resolve("connectors").resolve("delivery.json");
      if (Files.isRegularFile(file)) {
        try {
          return JsonMapper.builder().build().readTree(Files.readAllBytes(file));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    throw new IllegalStateException("connectors/delivery.json was not found above " + Path.of("").toAbsolutePath());
  }
}
