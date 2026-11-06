package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Private file handoff. Neither MCP responses nor admin review views contain imported secret values. */
@Component
public class ConnectionImportFiles {

  private static final int SECRET_BYTES = 64 * 1024;
  private final Path directory;
  private final JsonMapper json;
  private boolean cleanupFailed;

  public ConnectionImportFiles(GatewayPaths paths, JsonMapper json) {
    this.directory = paths.home().resolve("connection-imports");
    this.json = json;
    removeOrphans();
  }

  /** Session capabilities do not survive a core restart, so their unconsumed files must not survive either. */
  private void removeOrphans() {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
    try {
      requireDirectory();
      try (var entries = Files.list(directory)) {
        for (Path file : entries.filter(p -> p.getFileName().toString().matches("ci_[a-z0-9]{12}\\.json")).toList()) {
          if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) Files.deleteIfExists(file);
        }
      }
    } catch (IOException | ProblemException e) {
      cleanupFailed = true;
    }
  }

  /** Creates an empty, owner-only file at a server-chosen path. The AI writes exactly this file. */
  public String create(String id) {
    if (cleanupFailed) throw invalid("The previous import files could not be removed. Check the private import directory's permissions and restart GateRook.");
    Path path = path(id);
    try {
      if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
        try {
          Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
          Files.createDirectory(directory);
        }
      }
      requireDirectory();
      try {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } catch (UnsupportedOperationException e) {
        Files.createFile(path);
      }
      return path.toString();
    } catch (IOException e) {
      throw invalid("GateRook could not prepare the private import file. Start a new import.");
    }
  }

  /** Reads only the predetermined file, removes values from the review manifest, then deletes the plaintext file. */
  public Prepared read(String id) {
    Map<String, Map<String, StagedSecret>> staged = new LinkedHashMap<>();
    byte[] bytes = null;
    try {
      requireDirectory();
      bytes = readBytes(path(id), ConnectionImportService.MAX_BYTES);
      JsonNode parsed = json.readTree(bytes);
      if (!(parsed instanceof ObjectNode manifest) || !manifest.path("connections").isArray()
          || manifest.path("connections").size() > ConnectionImportService.MAX_CONNECTIONS) {
        throw invalid("The import file must contain a manifest with at most 50 connections.");
      }
      for (JsonNode node : manifest.path("connections")) {
        if (!(node instanceof ObjectNode candidate) || !candidate.path("id").isString()) {
          throw invalid("Every imported connection needs an id.");
        }
        String candidateId = candidate.path("id").asString();
        if (staged.containsKey(candidateId) || candidate.has("secretSources")) {
          throw invalid("Use unique connection ids and put credentials only in secrets.");
        }
        Map<String, StagedSecret> fields = new LinkedHashMap<>();
        staged.put(candidateId, fields);
        JsonNode secrets = candidate.remove("secrets");
        ArrayNode summaries = JsonNodeFactory.instance.arrayNode();
        if (secrets != null) {
          if (!secrets.isObject() || secrets.size() > 32) throw invalid("Use a map of at most 32 secret fields per connection.");
          for (Map.Entry<String, JsonNode> entry : secrets.properties()) {
            String field = entry.getKey();
            if (!field.startsWith("/") || field.length() > 256 || controls(field)) {
              throw invalid("Secret field names must be JSON pointers from the connector schema.");
            }
            StagedSecret secret = parse(entry.getValue());
            fields.put(field, secret);
            summaries.add(secret.summary(field));
          }
        }
        if (!summaries.isEmpty()) candidate.set("secretSources", summaries);
      }
      Files.delete(path(id));
      return new Prepared(manifest, staged);
    } catch (ProblemException e) {
      clear(staged);
      throw e;
    } catch (RuntimeException | IOException e) {
      clear(staged);
      // Parser exceptions can include source snippets, paths and values. Never propagate them.
      throw invalid("The import file could not be read. Use valid UTF-8 JSON within the import size limit.");
    } finally {
      if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
  }

  public void delete(String id) {
    try {
      requireDirectory();
      Files.deleteIfExists(path(id));
    } catch (IOException | ProblemException ignored) {
      // Do not follow a replaced directory or expose file errors through session cleanup.
    }
  }

  public record Prepared(ObjectNode manifest, Map<String, Map<String, StagedSecret>> secrets) implements AutoCloseable {
    public Map<String, SecretInput> resolve(String candidateId) {
      Map<String, SecretInput> resolved = new LinkedHashMap<>();
      try {
        for (Map.Entry<String, StagedSecret> field : secrets.getOrDefault(candidateId, Map.of()).entrySet()) {
          resolved.put(field.getKey(), field.getValue().resolve());
        }
        return resolved;
      } catch (RuntimeException e) {
        resolved.values().forEach(SecretInput::clear);
        throw e;
      }
    }

    @Override public void close() { clear(secrets); }
    @Override public String toString() { return "Prepared[credentials redacted]"; }
  }

  public static final class StagedSecret {
    private final String source;
    private final char[] value;
    private final String reference;
    private final String store;
    private final Path file;
    private final String pointer;
    private final JsonMapper json;

    private StagedSecret(String source, char[] value, String reference, String store, Path file, String pointer,
        JsonMapper json) {
      this.source = source;
      this.value = value;
      this.reference = reference;
      this.store = store;
      this.file = file;
      this.pointer = pointer;
      this.json = json;
    }

    /** Called only after the person confirms the selected connection and its displayed sources in the admin UI. */
    public SecretInput resolve() {
      if (value != null) return SecretInput.local(value.clone());
      if (file == null) return "vault-dynamic".equals(source)
          ? SecretInput.dynamic(reference, store) : SecretInput.reference(reference, store);
      byte[] bytes = null;
      try {
        bytes = readBytes(file, SECRET_BYTES);
        if (pointer != null) {
          JsonNode selected = json.readTree(bytes).at(pointer);
          if (!selected.isString()) throw invalid("The selected JSON file key must contain a string secret.");
          return SecretInput.local(selected.asString().toCharArray());
        }
        var decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
        char[] chars = new char[decoded.remaining()];
        decoded.get(chars);
        if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
        return SecretInput.local(chars);
      } catch (ProblemException e) {
        throw e;
      } catch (RuntimeException | IOException e) {
        throw invalid("A selected credential file is missing, unsafe, too large or not valid UTF-8/JSON. Review its source.");
      } finally {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
      }
    }

    public ObjectNode summary(String field) {
      ObjectNode summary = JsonNodeFactory.instance.objectNode().put("field", field).put("source", source);
      if (reference != null) summary.put("reference", reference);
      if (store != null) summary.put("store", store);
      if (file != null) summary.put("path", file.toString());
      if (pointer != null) summary.put("jsonPointer", pointer);
      return summary;
    }

    public void clear() { if (value != null) Arrays.fill(value, '\0'); }
    @Override public String toString() { return "StagedSecret[source=" + source + ", value=redacted]"; }
  }

  private StagedSecret parse(JsonNode input) {
    if (input == null || !input.isObject()) throw invalid("Every secret must identify its source.");
    String source = text(input, "source", 32, true);
    Set<String> allowed = switch (source) {
      case "local" -> Set.of("source", "value");
      case "file" -> Set.of("source", "path", "jsonPointer");
      case "reference", "vault-dynamic" -> Set.of("source", "ref", "store");
      default -> throw invalid("Secret source must be local, file, reference or vault-dynamic.");
    };
    for (String key : input.propertyNames()) if (!allowed.contains(key)) throw invalid("Unknown property in a secret source.");
    if (source.equals("local")) {
      JsonNode value = input.path("value");
      if (!value.isString() || value.asString().length() > SECRET_BYTES) throw invalid("A literal secret must be a string of at most 64 KiB.");
      return new StagedSecret(source, value.asString().toCharArray(), null, null, null, null, json);
    }
    if (source.equals("file")) {
      Path file;
      try { file = Path.of(text(input, "path", 2048, true)); }
      catch (RuntimeException e) { throw invalid("A credential file needs an absolute filesystem path."); }
      if (!file.isAbsolute()) throw invalid("A credential file needs an absolute filesystem path.");
      String pointer = text(input, "jsonPointer", 512, false);
      if (pointer != null && (!pointer.startsWith("/") || pointer.matches(".*~(?:[^01]|$).*"))) {
        throw invalid("jsonPointer must be an RFC 6901 pointer to a string in a JSON file.");
      }
      return new StagedSecret(source, null, null, null, file.normalize(), pointer, json);
    }
    String reference = text(input, "ref", 1024, true);
    // These are inert references. The secrets module performs canonical parsing and store binding on confirmation.
    if (!reference.matches("(?:vault|gcp-sm|aws-sm)://[^\\s%\\p{Cntrl}]+")) {
      throw invalid("Use a Vault, GCP Secret Manager or AWS Secrets Manager reference.");
    }
    if (source.equals("vault-dynamic") && !reference.matches("vault://[^?#]+/creds/[^/?#]+")) {
      throw invalid("Dynamic credentials require a Vault credentials role reference.");
    }
    return new StagedSecret(source, null, reference, text(input, "store", 128, false), null, null, json);
  }

  private Path path(String id) {
    if (!Ids.matches("ci_", id)) throw invalid("Invalid import session.");
    return directory.resolve(id + ".json");
  }

  private void requireDirectory() throws IOException {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
      throw invalid("The private import directory is unavailable.");
    }
    if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
  }

  private static byte[] readBytes(Path path, int limit) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw invalid("Choose a regular file, not a symbolic link or directory.");
    }
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = Files.newByteChannel(path, options)) {
      if (channel.size() > limit) throw invalid("The import or credential file exceeds its size limit.");
      ByteBuffer bytes = ByteBuffer.allocate(limit + 1);
      try {
        while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
        if (bytes.position() > limit) throw invalid("The import or credential file exceeds its size limit.");
        return Arrays.copyOf(bytes.array(), bytes.position());
      } finally {
        Arrays.fill(bytes.array(), (byte) 0);
      }
    }
  }

  private static String text(JsonNode node, String field, int limit, boolean required) {
    JsonNode value = node.get(field);
    if (value == null && !required) return null;
    if (value == null || !value.isString() || value.asString().isBlank() || value.asString().length() > limit
        || controls(value.asString())) throw invalid("A secret source contains a missing or invalid field.");
    return value.asString();
  }

  private static boolean controls(String value) { return value.chars().anyMatch(c -> c < 32 || c == 127); }
  private static void clear(Map<String, Map<String, StagedSecret>> secrets) {
    secrets.values().forEach(fields -> fields.values().forEach(StagedSecret::clear));
  }
  private static ProblemException invalid(String message) {
    return ProblemException.unprocessable("Check the import file", message, "/manifest");
  }
}
