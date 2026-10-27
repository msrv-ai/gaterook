package ai.msrv.gaterook.connectors.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON Schemas of one connector, kept as classpath resources {@code connectors/<directory>/<name>.schema.json}.
 *
 * <p>Schemas are data, not code: reviewers diff them like the reports they come from, and the UI renders forms from
 * them without changes. Each connector module registers its directory for the native image
 * ({@code RuntimeHints.resources().registerPattern("connectors/<directory>/*.schema.json")}); a resource without
 * that hint is {@code null} at run time in native (report 15 §3).
 *
 * <p>Trees are parsed on every {@link #load} call; connectors parse their schemas once, when they build their
 * manifest. Callers must not mutate a returned tree that they have published: the core copies a tool's schema
 * before it fills the {@code connection} enum for an endpoint.
 */
public final class SchemaResources {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final ClassLoader classLoader;
  private final String directory;

  private SchemaResources(ClassLoader classLoader, String directory) {
    this.classLoader = classLoader;
    this.directory = directory;
  }

  /**
   * @param anchor    a class of the connector module, whose class loader sees its resources
   * @param directory the connector's directory under {@code connectors/}, e.g. {@code "sql"}
   */
  public static SchemaResources of(Class<?> anchor, String directory) {
    Objects.requireNonNull(anchor, "anchor");
    if (directory == null || !directory.matches("[a-z0-9-]+")) {
      throw new IllegalArgumentException("Bad schema directory: " + directory);
    }
    return new SchemaResources(anchor.getClassLoader(), directory);
  }

  /** The classpath pattern a module registers as a native-image resource hint. */
  public String resourcePattern() {
    return "connectors/" + directory + "/*.schema.json";
  }

  /**
   * Parses {@code connectors/<directory>/<name>.schema.json}.
   *
   * @throws IllegalStateException if the resource is missing or isn't a JSON object: a packaging bug that must stop
   *                               the connector from registering rather than publish a tool without a schema
   */
  public JsonNode load(String name) {
    String path = "connectors/" + directory + "/" + name + ".schema.json";
    try (InputStream in = classLoader.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Schema resource " + path + " is missing from the classpath");
      }
      JsonNode tree = MAPPER.readTree(in);
      if (tree == null || !tree.isObject()) {
        throw new IllegalStateException("Schema resource " + path + " is not a JSON object");
      }
      return tree;
    } catch (IOException e) {
      throw new UncheckedIOException("Can't read schema resource " + path, e);
    } catch (JacksonException e) {
      throw new IllegalStateException("Schema resource " + path + " is not valid JSON: " + e.getOriginalMessage(), e);
    }
  }
}
