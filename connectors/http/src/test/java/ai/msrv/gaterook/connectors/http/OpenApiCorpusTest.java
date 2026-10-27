package ai.msrv.gaterook.connectors.http;

import static ai.msrv.gaterook.connectors.http.Fixtures.cfg;
import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connectors.support.TemplateImporter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The real descriptions of report 10 §8.5 ({@code -Popenapi-corpus}): Stripe's {@code spec3.json} (8.3 MB, 3.0) and
 * GitHub's {@code api.github.com.json} (13.6 MB, 3.1), downloaded at pinned commits into
 * {@code ~/.cache/mcp-gateway-test/openapi/} and checked by SHA-256. Every operation imports and compiles in chunks of
 * at most 200 without an exception, every input schema is JSON Schema 2020-12 of at most 32 KB, and GitHub's file reads
 * in under 300 ms (median after warm-up; ARCHITECTURE.md §10 C9).
 */
@Tag("openapi-corpus")
class OpenApiCorpusTest {

  private static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test", "openapi");
  private static final OpenApiTemplateKind KIND = (OpenApiTemplateKind) new HttpConnector().templateKinds().getFirst();

  enum Spec {
    STRIPE("stripe-spec3.json", "https://raw.githubusercontent.com/stripe/openapi/"
        + "52c7bc387eed75e31d86359708cb49748ef803cd/openapi/spec3.json",
        "7cff4cc46d0654101a36a3302f7544640f5f73e4f3cad6f264f0e23c19fa1776", "stripe", "https://api.stripe.com",
        "{\"type\":\"bearer\"}", 2_000),
    GITHUB("github-api.github.com.json", "https://raw.githubusercontent.com/github/rest-api-description/"
        + "755ee33b4a81d51f6f07a902cce26cbec3b822f2/descriptions-next/api.github.com/api.github.com.json",
        "f027468cd77c110f1d813ed8d7bd8b353126f8f35ae4bcbba6d5f7be70304834", "github", "https://api.github.com",
        "{\"type\":\"bearer\"}", 300);

    final String file;
    final String url;
    final String sha256;
    final String connection;
    final String baseUrl;
    final String auth;
    /** Median time of {@code importDocument} (read, every operation converted) after warm-up. */
    final long importBudgetMs;

    Spec(String file, String url, String sha256, String connection, String baseUrl, String auth, long importBudgetMs) {
      this.file = file;
      this.url = url;
      this.sha256 = sha256;
      this.connection = connection;
      this.baseUrl = baseUrl;
      this.auth = auth;
      this.importBudgetMs = importBudgetMs;
    }
  }

  /** The cached file, downloaded first if missing; a file whose hash differs is removed and fails the test. */
  static byte[] fetch(Spec spec) throws IOException, InterruptedException {
    Path target = CACHE.resolve(spec.file);
    if (!Files.exists(target)) {
      Files.createDirectories(CACHE);
      Path partial = CACHE.resolve(spec.file + ".part");
      try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
          .followRedirects(HttpClient.Redirect.NORMAL).build()) {
        HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(spec.url))
            .timeout(Duration.ofMinutes(3)).build(), HttpResponse.BodyHandlers.ofFile(partial));
        assertThat(response.statusCode()).as(spec.url).isEqualTo(200);
      }
      Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    }
    byte[] bytes = Files.readAllBytes(target);
    String actual;
    try {
      actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    if (!actual.equals(spec.sha256)) {
      Files.delete(target);
    }
    assertThat(actual).as("SHA-256 of " + spec.file + " (removed; the next run downloads it again)")
        .isEqualTo(spec.sha256);
    return bytes;
  }

  @ParameterizedTest
  @EnumSource(Spec.class)
  void everyOperationImportsAndCompiles(Spec spec) throws Exception {
    byte[] bytes = fetch(spec);
    ConnectionConfig cfg = cfg(spec.connection, Env.DEV, "{\"baseUrls\":[\"" + spec.baseUrl + "\"],\"auth\":"
        + spec.auth + "}");

    TemplateImporter.Result all = KIND.importDocument(bytes, cfg, null);
    assertThat(all.errors()).isEmpty();
    JsonNode root = OpenApiDocument.read(bytes);
    int declared = 0;
    for (Map.Entry<String, JsonNode> path : root.path("paths").properties()) {
      for (String method : path.getValue().propertyNames()) {
        declared += OpenApiDocument.METHODS.contains(method) ? 1 : 0;
      }
    }
    assertThat(all.operations()).hasSize(declared);
    List<String> usable = all.operations().stream().filter(o -> o.skipped() == null)
        .map(TemplateImporter.Operation::opKey).toList();
    System.out.printf("%s: %d operations, %d usable, %d skipped%n", spec.file, declared, usable.size(),
        declared - usable.size());
    all.operations().stream().filter(o -> o.skipped() != null)
        .forEach(o -> System.out.printf("  skipped %s: %s%n", o.opKey(), o.skipped()));
    assertThat(usable.size()).as("usable operations").isGreaterThan(declared * 9 / 10);
    // The hostile-file bounds leave real descriptions far inside them.
    OpenApiImport counted = new OpenApiImport(root, java.util.Set.of(), null, OpenApiImport.Reserved.NONE);
    counted.entries("x");
    System.out.printf("%s: work %,d of %,d, %d operations of %,d%n", spec.file, counted.work(),
        OpenApiSchemas.MAX_TOTAL_WORK, declared, OpenApiImport.MAX_FILE_OPERATIONS);
    assertThat(counted.work()).isLessThan(OpenApiSchemas.MAX_TOTAL_WORK / 4);
    assertThat(bytes.length).isLessThan((int) (OpenApiDocument.expansionBudget(bytes.length) / 2));

    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    long started = System.nanoTime();
    int tools = 0;
    for (int from = 0; from < usable.size(); from += OpenApiTemplateKind.MAX_OPERATIONS) {
      List<String> chunk = usable.subList(from, Math.min(usable.size(), from + OpenApiTemplateKind.MAX_OPERATIONS));
      ObjectNode options = JsonNodeFactory.instance.objectNode();
      ArrayNode select = options.putArray("select");
      chunk.forEach(select::add);
      TemplateImporter.Result picked = KIND.importDocument(bytes, cfg, options);
      assertThat(picked.errors()).isEmpty();
      TemplateKind.CompileResult compiled = KIND.compile(picked.definition(), cfg);
      assertThat(compiled.errors()).as(spec.file + " operations " + from + "…").isEmpty();
      assertThat(compiled.tools()).hasSize(chunk.size());
      for (TemplateKind.CompiledTool t : compiled.tools()) {
        JsonNode schema = t.definition().inputSchema();
        assertThat(JsonText.compact(schema).getBytes(StandardCharsets.UTF_8).length).as(t.opKey())
            .isLessThanOrEqualTo(OpenApiImport.MAX_INPUT_SCHEMA_BYTES);
        assertThat(meta.validate(schema)).as(t.opKey()).isEmpty();
        assertThat(t.definition().name()).matches("[a-z][a-z0-9_]{0,63}");
        assertThat(t.definition().description().length()).isLessThanOrEqualTo(ToolText.TOOL_DESCRIPTION);
      }
      tools += compiled.tools().size();
    }
    System.out.printf("%s: %d tools in %d ms (import and compile in chunks of 200, with validation)%n", spec.file,
        tools, (System.nanoTime() - started) / 1_000_000);
    assertThat(tools).isEqualTo(usable.size());
  }

  @ParameterizedTest
  @EnumSource(Spec.class)
  void theFileReadsWithinItsBudget(Spec spec) throws Exception {
    byte[] bytes = fetch(spec);
    ConnectionConfig cfg = cfg(spec.connection, Env.DEV, "{\"baseUrls\":[\"" + spec.baseUrl + "\"],\"auth\":"
        + spec.auth + "}");
    for (int i = 0; i < 5; i++) {
      KIND.importDocument(bytes, cfg, null);
    }
    long[] ms = new long[9];
    for (int i = 0; i < ms.length; i++) {
      long started = System.nanoTime();
      TemplateImporter.Result r = KIND.importDocument(bytes, cfg, null);
      ms[i] = (System.nanoTime() - started) / 1_000_000;
      assertThat(r.errors()).isEmpty();
    }
    long[] sorted = ms.clone();
    Arrays.sort(sorted);
    List<Long> runs = new ArrayList<>();
    for (long m : ms) {
      runs.add(m);
    }
    System.out.printf("%s (%.1f MB): import median %d ms, runs %s%n", spec.file, bytes.length / 1_000_000.0,
        sorted[sorted.length / 2], runs);
    assertThat(sorted[sorted.length / 2]).as("median import of " + spec.file + " in ms")
        .isLessThan(spec.importBudgetMs);
  }
}
