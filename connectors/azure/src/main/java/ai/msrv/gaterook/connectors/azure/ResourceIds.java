package ai.msrv.gaterook.connectors.azure;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Azure Resource Manager paths and resource IDs ({@code /subscriptions/<id>/resourceGroups/<rg>/providers/
 * Microsoft.Web/sites/<name>/config/web}), read without I/O. ARM treats IDs case-insensitively, so every comparison
 * here is on the lower-case form; the path a request carries keeps the case it was given.
 *
 * <p>A plain path has no empty, dot or dot-dot segment and no {@code %}, {@code ?}, {@code #}, backslash or control
 * character: every segment is percent-encoded by the gateway when the URL is built ({@link #encodedPath}), so a path
 * can never name another place than the one checked.
 */
final class ResourceIds {

  /** One path segment: letters, digits and the punctuation resource names use; never {@code / ? # % \}. */
  private static final Pattern SEGMENT = Pattern.compile("^[\\p{L}\\p{N}._~()!,:@$'*+;= -]{1,260}$");
  static final int MAX_PATH = 2_048;

  /**
   * What a path names.
   *
   * @param subscription  the subscription ID, lower case
   * @param resourceGroup the resource group, or {@code null}
   * @param namespaces    the provider namespaces in the path, lower case, in order ({@code microsoft.web},
   *                      {@code microsoft.insights} for an extension resource)
   * @param type          the type of the resource the path is about ({@code microsoft.web/sites}), or {@code null}
   * @param name          its name, or {@code null}
   * @param segments      every segment as given
   */
  record Parsed(String subscription, String resourceGroup, List<String> namespaces, String type, String name,
      List<String> segments) {

    /** Whether the path names a whole resource ({@code …/providers/<ns>/<type>/<name>}), maybe with more after. */
    boolean isResource() {
      return type != null && name != null;
    }

    /** The path in lower case, as policies compare it. */
    String lower() {
      return "/" + String.join("/", segments).toLowerCase(Locale.ROOT);
    }

    /** The last segment, lower case: an action ({@code restart}, {@code listkeys}) or a collection. */
    String last() {
      return segments.getLast().toLowerCase(Locale.ROOT);
    }

    /** Every prefix of the path that ends at a segment, shortest first: masks match a resource or what is in it. */
    List<String> prefixes() {
      List<String> out = new ArrayList<>();
      StringBuilder b = new StringBuilder();
      for (String s : segments) {
        b.append('/').append(s.toLowerCase(Locale.ROOT));
        out.add(b.toString());
      }
      return out;
    }
  }

  private ResourceIds() {}

  /** The path's parts, or {@code null} when it isn't a plain path under {@code /subscriptions/<GUID>}. */
  static Parsed parse(String path) {
    if (path == null || path.length() > MAX_PATH || !path.startsWith("/") || path.endsWith("/")) {
      return null;
    }
    String[] raw = path.substring(1).split("/", -1);
    List<String> segments = new ArrayList<>();
    for (String s : raw) {
      if (s.isEmpty() || s.equals(".") || s.equals("..") || !SEGMENT.matcher(s).matches() || !s.strip().equals(s)) {
        return null;
      }
      segments.add(s);
    }
    if (segments.size() < 2 || !segments.get(0).equalsIgnoreCase("subscriptions")
        || !Settings.GUID.matcher(segments.get(1).toLowerCase(Locale.ROOT)).matches()) {
      return null;
    }
    String subscription = segments.get(1).toLowerCase(Locale.ROOT);
    int i = 2;
    String group = null;
    if (i < segments.size() && segments.get(i).equalsIgnoreCase("resourceGroups")) {
      if (i + 1 >= segments.size()) {
        return new Parsed(subscription, null, List.of(), null, null, List.copyOf(segments));
      }
      group = segments.get(i + 1);
      i += 2;
    }
    List<String> namespaces = new ArrayList<>();
    String type = null;
    String name = null;
    while (i < segments.size()) {
      if (segments.get(i).equalsIgnoreCase("providers") && i + 1 < segments.size()) {
        String ns = segments.get(i + 1).toLowerCase(Locale.ROOT);
        namespaces.add(ns);
        if (type == null && i + 2 < segments.size()) {
          type = ns + "/" + segments.get(i + 2).toLowerCase(Locale.ROOT);
          name = i + 3 < segments.size() ? segments.get(i + 3) : null;
        }
        i += 2;
      } else {
        i++;
      }
    }
    return new Parsed(subscription, group, List.copyOf(namespaces), type, name, List.copyOf(segments));
  }

  /** {@code /subscriptions/…}, each segment percent-encoded as an RFC 3986 {@code pchar}. */
  static String encodedPath(Parsed p) {
    StringBuilder out = new StringBuilder();
    for (String s : p.segments()) {
      out.append('/').append(encode(s));
    }
    return out.toString();
  }

  static String encode(String segment) {
    StringBuilder out = new StringBuilder();
    for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xFF;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-._~!$'()*+,;=:@".indexOf(c)
          >= 0) {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
      }
    }
    return out.toString();
  }

  /**
   * The provider namespace of a resource type ({@code microsoft.compute/virtualmachines} → {@code microsoft.compute}),
   * lower case; {@code null} for a value without one.
   */
  static String namespaceOfType(String type) {
    if (type == null) {
      return null;
    }
    int slash = type.indexOf('/');
    return slash <= 0 ? null : type.substring(0, slash).toLowerCase(Locale.ROOT);
  }
}
