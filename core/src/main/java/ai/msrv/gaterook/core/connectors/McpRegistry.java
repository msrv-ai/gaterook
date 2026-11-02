package ai.msrv.gaterook.core.connectors;

import ai.msrv.gaterook.core.connectors.plugins.CatalogDownloads;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Search of an MCP registry for Add MCP server (ARCHITECTURE.md §7.5): a server with the read API of the Official MCP
 * Registry ({@code GET /v0.1/servers}), by default that registry itself ({@code gateway.mcp-registry.url}; empty turns
 * the search off). A registry may add GateRook's facts about an entry under {@code _meta["dev.msrv.gaterook/catalog"]}:
 * how its publisher was verified, whether it is featured, its category. The official registry adds none.
 *
 * <p>Requests go through the catalog's egress guard ({@link CatalogDownloads}: {@code https://} only, bounded, never
 * this computer). The answer is third-party data: each entry is checked and cut down to the names, texts, remote URLs
 * and package facts the dialog fills in; texts lose control and format characters (bidi overrides, zero-width
 * characters); deleted entries are skipped. Nothing here adds or starts a server: a result only fills the form, and
 * its review checks the server itself.
 */
@Component
public class McpRegistry {

  /** The Official MCP Registry. */
  public static final String OFFICIAL = "https://registry.modelcontextprotocol.io";
  /** Where a registry puts GateRook's facts about an entry, in the result's or the server's {@code _meta}. */
  static final String LISTING_META = "dev.msrv.gaterook/catalog";
  static final String OFFICIAL_META = "io.modelcontextprotocol.registry/official";
  static final int PAGE_SIZE = 20;
  static final long MAX_PAGE_BYTES = 4L * 1024 * 1024;
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final Logger log = LoggerFactory.getLogger(McpRegistry.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  /** A registry name: a reverse-DNS namespace, a slash, the server's own name ({@code app.linear/linear}). */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9.-]{1,128}/[A-Za-z0-9._-]{1,128}");
  private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
  private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final Pattern ARGUMENT_NAME = Pattern.compile("-{0,2}[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Set<String> REMOTES = Set.of("streamable-http", "sse");
  private static final Set<String> TRANSPORTS = Set.of("stdio", "streamable-http", "sse");
  private static final Set<String> VERIFICATIONS = Set.of("vendor", "publisher", "community");

  /**
   * One page of results, the latest version of each server.
   *
   * @param nextCursor where the next page starts, or null after the last one
   * @param registry   the registry's host, for the line that says where the results come from
   */
  public record Page(List<Server> servers, String nextCursor, String registry) {}

  /**
   * One server.
   *
   * @param name    the registry's name: the publisher's verified namespace, a slash and the server's own name
   * @param status  {@code active} or {@code deprecated}
   * @param listing GateRook's facts, when the registry adds them; null otherwise
   */
  public record Server(String name, String title, String description, String version, String websiteUrl,
      String repositoryUrl, String status, List<Remote> remotes, List<Package> packages, Listing listing) {}

  /**
   * A server at a URL, {@code streamable-http} or {@code sse}. A URL with {@code {variables}} is a template whose
   * parts the person fills in.
   */
  public record Remote(String type, String url, List<Input> headers, List<Input> variables) {}

  /**
   * A package that runs on this computer: {@code npm}, {@code pypi}, {@code oci}, {@code nuget}, {@code mcpb} or another
   * registry's, with the runtime the entry names ({@code npx}, {@code uvx}, {@code docker}) and its transport
   * ({@code stdio}, or {@code streamable-http} and {@code sse} for a package that serves HTTP itself).
   */
  public record Package(String registryType, String identifier, String version, String runtimeHint, String transport,
      List<Input> environmentVariables, List<Argument> runtimeArguments, List<Argument> packageArguments) {}

  /** A header, URL variable or environment variable the person gives; values are never taken from the registry. */
  public record Input(String name, String description, boolean required, boolean secret) {}

  /**
   * A command-line argument: {@code positional}, or {@code named} ({@code --port 8080}). {@code given} is what the entry
   * gives for it, its fixed value or its default, and may hold {@code {variables}}; {@code valueHint} names what goes
   * there. (Not {@code value}: no admin answer has a property of that name, ARCHITECTURE.md §9.)
   */
  public record Argument(String type, String name, String given, String valueHint, boolean required) {}

  /**
   * GateRook's facts about an entry, when the registry adds them.
   *
   * @param verification {@code vendor} (the namespace's domain serves it), {@code publisher} (a vendor's GitHub
   *                     organization) or {@code community}
   */
  public record Listing(String verification, boolean featured, String category) {}

  /** The registry didn't give an answer this core can use: what went wrong, in the app's words. */
  public static final class UnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final CatalogDownloads downloads;
  private final HttpUrl base;
  private final String problem;

  McpRegistry(CatalogDownloads downloads, @Value("${gateway.mcp-registry.url:" + OFFICIAL + "}") String url) {
    this.downloads = downloads;
    HttpUrl parsed = null;
    String why = null;
    if (url == null || url.isBlank()) {
      why = "This build of GateRook has no MCP registry to search.";
    } else {
      try {
        parsed = CatalogDownloads.https(url.strip().replaceAll("/+$", ""), "the MCP registry");
      } catch (CatalogDownloads.DownloadException e) {
        why = "Search needs the https:// address of an MCP registry in gateway.mcp-registry.url.";
        log.warn("MCP registry search is off: {}", e.getMessage());
      }
    }
    this.base = parsed;
    this.problem = why;
  }

  /**
   * The servers whose names contain {@code query} (all servers when it is empty), {@value #PAGE_SIZE} at a time; a
   * registry with GateRook's data may also match titles and descriptions.
   *
   * @param cursor the {@code nextCursor} of the previous page, or null for the first
   * @throws ProblemException     409 when no registry is set, 422 for a query or cursor the registry won't take
   * @throws UnavailableException when the registry can't be reached or answers with something else than a page
   */
  public Page search(String query, String cursor) {
    if (base == null) {
      throw ProblemException.conflict("Registry search is off", problem);
    }
    String q = query == null ? "" : query.strip();
    if (!q.isEmpty() && q.length() < 3) {
      throw ProblemException.unprocessable("Search too short", "Type at least 3 characters.", "/search");
    }
    if (q.length() > 100 || q.chars().anyMatch(Character::isISOControl)) {
      throw ProblemException.unprocessable("Bad search", "Search for at most 100 characters of a server's name.",
          "/search");
    }
    if (cursor != null && (cursor.length() > 512 || cursor.chars().anyMatch(Character::isISOControl))) {
      throw ProblemException.unprocessable("Bad cursor", "Start the search again.", "/cursor");
    }
    HttpUrl.Builder url = base.newBuilder().addPathSegments("v0.1/servers").addQueryParameter("version", "latest")
        .addQueryParameter("limit", Integer.toString(PAGE_SIZE));
    if (!q.isEmpty()) {
      url.addQueryParameter("search", q);
    }
    if (cursor != null && !cursor.isEmpty()) {
      url.addQueryParameter("cursor", cursor);
    }
    byte[] body;
    try {
      body = downloads.get(url.build(), MAX_PAGE_BYTES, TIMEOUT, "application/json", "the MCP registry's answer");
    } catch (CatalogDownloads.DownloadException e) {
      throw new UnavailableException(e.getMessage(), e);
    }
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JacksonException e) {
      throw new UnavailableException(base.host() + " didn't answer with JSON.", e);
    }
    if (!root.path("servers").isArray()) {
      throw new UnavailableException(base.host() + " didn't answer with a list of servers.", null);
    }
    List<Server> servers = new ArrayList<>();
    for (JsonNode item : root.path("servers")) {
      if (servers.size() == PAGE_SIZE) {
        break;
      }
      Server s = server(item);
      if (s != null) {
        servers.add(s);
      }
    }
    String next = text(root.path("metadata").path("nextCursor"), 512);
    return new Page(List.copyOf(servers), next == null || next.isEmpty() ? null : next, base.host());
  }

  // ── Reading an entry ──────────────────────────────────────────────────────────────────────────────────────────

  private static Server server(JsonNode item) {
    JsonNode s = item.path("server");
    String name = s.path("name").isString() ? s.get("name").asString() : "";
    if (!NAME.matcher(name).matches()) {
      return null;
    }
    String status = item.path("_meta").path(OFFICIAL_META).path("status").asString("active")
        .toLowerCase(Locale.ROOT);
    if (status.equals("deleted")) {
      return null;
    }
    List<Remote> remotes = new ArrayList<>();
    for (JsonNode r : s.path("remotes")) {
      Remote remote = remote(r);
      if (remote != null && remotes.size() < 8) {
        remotes.add(remote);
      }
    }
    List<Package> packages = new ArrayList<>();
    for (JsonNode p : s.path("packages")) {
      Package pkg = pkg(p);
      if (pkg != null && packages.size() < 8) {
        packages.add(pkg);
      }
    }
    JsonNode listing = item.path("_meta").path(LISTING_META);
    return new Server(name, text(s.path("title"), 100), text(s.path("description"), 300),
        text(s.path("version"), 64), https(s.path("websiteUrl")), https(s.path("repository").path("url")),
        status.equals("deprecated") ? "deprecated" : "active", List.copyOf(remotes), List.copyOf(packages),
        listing(listing.isObject() ? listing : s.path("_meta").path(LISTING_META)));
  }

  private static Remote remote(JsonNode r) {
    String type = r.path("type").asString("").toLowerCase(Locale.ROOT);
    String url = https(r.path("url"));
    if (!REMOTES.contains(type) || url == null) {
      return null;
    }
    List<Input> headers = inputs(r.path("headers"), HEADER, 16);
    List<Input> variables = new ArrayList<>();
    if (r.path("variables").isObject()) {
      for (Map.Entry<String, JsonNode> v : r.get("variables").properties()) {
        if (VARIABLE.matcher(v.getKey()).matches() && variables.size() < 16) {
          variables.add(input(v.getKey(), v.getValue()));
        }
      }
    }
    return new Remote(type, url, headers, List.copyOf(variables));
  }

  private static Package pkg(JsonNode p) {
    String registryType = token(p.path("registryType"), 32);
    String identifier = text(p.path("identifier"), 255);
    if (registryType == null || identifier == null || identifier.isEmpty() || identifier.contains(" ")) {
      return null;
    }
    String transport = p.path("transport").path("type").asString("stdio").toLowerCase(Locale.ROOT);
    return new Package(registryType, identifier, token(p.path("version"), 64), token(p.path("runtimeHint"), 32),
        TRANSPORTS.contains(transport) ? transport : null, inputs(p.path("environmentVariables"), VARIABLE, 32),
        arguments(p.path("runtimeArguments")), arguments(p.path("packageArguments")));
  }

  private static List<Input> inputs(JsonNode list, Pattern names, int max) {
    List<Input> out = new ArrayList<>();
    for (JsonNode i : list) {
      String name = i.path("name").asString("");
      if (names.matcher(name).matches() && out.size() < max) {
        out.add(input(name, i));
      }
    }
    return List.copyOf(out);
  }

  private static Input input(String name, JsonNode i) {
    return new Input(name, text(i.path("description"), 200), i.path("isRequired").asBoolean(false),
        i.path("isSecret").asBoolean(false));
  }

  private static List<Argument> arguments(JsonNode list) {
    List<Argument> out = new ArrayList<>();
    for (JsonNode a : list) {
      String type = a.path("type").asString("").toLowerCase(Locale.ROOT);
      String name = a.path("name").asString(null);
      if (out.size() == 32 || !(type.equals("positional") || type.equals("named"))
          || (type.equals("named") && (name == null || !ARGUMENT_NAME.matcher(name).matches()))) {
        continue;
      }
      String value = text(a.path("value"), 256);
      out.add(new Argument(type, type.equals("named") ? name : null, value != null ? value
          : text(a.path("default"), 256), text(a.path("valueHint"), 64), a.path("isRequired").asBoolean(false)));
    }
    return List.copyOf(out);
  }

  private static Listing listing(JsonNode m) {
    if (!m.isObject()) {
      return null;
    }
    String verification = m.path("verification").asString("").toLowerCase(Locale.ROOT);
    return new Listing(VERIFICATIONS.contains(verification) ? verification : null,
        m.path("featured").asBoolean(false), text(m.path("category"), 64));
  }

  /** An {@code https://} URL without spaces or control characters, at most 2,048 characters; null otherwise. */
  private static String https(JsonNode n) {
    if (!n.isString()) {
      return null;
    }
    String url = n.asString();
    boolean ok = url.length() <= 2048 && url.regionMatches(true, 0, "https://", 0, 8) && url.length() > 8
        && url.chars().noneMatch(c -> c <= 0x20 || c == 0x7F || Character.getType(c) == Character.FORMAT);
    return ok ? url : null;
  }

  /** A word such as {@code npm} or {@code 1.2.3}: letters, digits and {@code .+_@-}; null otherwise. */
  private static String token(JsonNode n, int max) {
    String s = n.isString() ? n.asString().strip() : "";
    return !s.isEmpty() && s.length() <= max && s.matches("[A-Za-z0-9.+_@-]+") ? s : null;
  }

  /**
   * Text to show: without control and format characters, whitespace collapsed, cut to {@code max} characters with an
   * ellipsis; null when absent.
   */
  static String text(JsonNode n, int max) {
    if (!n.isString()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    n.asString().codePoints().forEach(c -> {
      int type = Character.getType(c);
      if (Character.isWhitespace(c) || type == Character.CONTROL) {
        if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
          out.append(' ');
        }
      } else if (type != Character.FORMAT && type != Character.UNASSIGNED && type != Character.SURROGATE
          && type != Character.PRIVATE_USE) {
        out.appendCodePoint(c);
      }
    });
    String s = out.toString().strip();
    if (s.codePointCount(0, s.length()) <= max) {
      return s;
    }
    return s.substring(0, s.offsetByCodePoints(0, max - 1)).stripTrailing() + "…";
  }
}
