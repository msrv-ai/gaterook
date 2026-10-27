package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A reference to a secret in an external store (report 12 §4.2): {@code vault://kv/stage/db#password},
 * {@code gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/latest},
 * {@code aws-sm://eu-central-1/stage/billing-api#webhook_secret}. Only a person sets one, in the window; no tool takes a
 * reference as an argument, so an agent can never name a secret.
 *
 * <p>The parser is our own rather than {@link java.net.URI}: a reference is not a URL (an AWS name has slashes, a key
 * may come before or after the query), and a lenient URL parser would let two spellings name the same secret.
 * Everything is checked against narrow character sets; percent-escapes, dot segments, empty segments, whitespace and
 * control characters are refused. {@link #uri()} is the one canonical spelling, the query before the key, which is
 * what is stored.
 */
public sealed interface SecretRef permits SecretRef.Vault, SecretRef.GcpSm, SecretRef.AwsSm {

  /** The canonical form. */
  String uri();

  /** The JSON key inside the secret, or {@code null} for the whole value. */
  String key();

  /** The kind of store that resolves it. */
  StoreKind kind();

  /** What messages name: {@code kv/stage/db}, {@code billing-api-token}, {@code stage/billing-api}. */
  String display();

  /** Whether a fixed version is named; {@code latest} and {@code AWSCURRENT} follow the store. */
  boolean pinned();

  int MAX_LENGTH = 1024;

  /**
   * A Vault/OpenBao secret: {@code vault://<path>[?version=<n>][#<key>]}. The mount isn't marked in the path; the
   * store asks Vault where it ends ({@code sys/internal/ui/mounts}). {@code vault://database/creds/<role>} names
   * dynamic credentials.
   */
  record Vault(String path, Integer version, String key) implements SecretRef {
    @Override
    public String uri() {
      return "vault://" + path + (version == null ? "" : "?version=" + version) + (key == null ? "" : "#" + key);
    }

    @Override
    public StoreKind kind() {
      return StoreKind.VAULT;
    }

    @Override
    public String display() {
      return path;
    }

    @Override
    public boolean pinned() {
      return version != null;
    }

    /** {@code <mount…>/creds/<role>}: the shape of a database (or other) dynamic credentials path. */
    public boolean credsPath() {
      int at = path.lastIndexOf("/creds/");
      return at > 0 && path.indexOf('/', at + "/creds/".length()) < 0 && version == null && key == null;
    }
  }

  /**
   * A GCP Secret Manager version: {@code gcp-sm://projects/<p>[/locations/<l>]/secrets/<s>/versions/<v|latest>
   * [#<jsonKey>]}. A location makes it a regional secret ({@code secretmanager.<l>.rep.googleapis.com}).
   */
  record GcpSm(String project, String location, String secret, String version, String key) implements SecretRef {
    @Override
    public String uri() {
      return "gcp-sm://" + resourceName() + (key == null ? "" : "#" + key);
    }

    /** {@code projects/acme-stage/secrets/billing-api-token/versions/latest}. */
    public String resourceName() {
      return secretName() + "/versions/" + version;
    }

    /** {@code projects/acme-stage/secrets/billing-api-token}. */
    public String secretName() {
      return "projects/" + project + (location == null ? "" : "/locations/" + location) + "/secrets/" + secret;
    }

    @Override
    public StoreKind kind() {
      return StoreKind.GCP_SM;
    }

    @Override
    public String display() {
      return secret;
    }

    @Override
    public boolean pinned() {
      return !"latest".equals(version);
    }
  }

  /**
   * An AWS Secrets Manager secret: {@code aws-sm://<region>/<name>[?versionStage=<s>|versionId=<id>][#<jsonKey>]}, or
   * {@code aws-sm://arn:aws:secretsmanager:<region>:<account>:secret:<name>…} (needed for another account).
   */
  record AwsSm(String region, String name, String arn, String versionStage, String versionId, String key)
      implements SecretRef {
    @Override
    public String uri() {
      String query = versionId != null ? "?versionId=" + versionId : versionStage != null ? "?versionStage="
          + versionStage : "";
      return "aws-sm://" + (arn != null ? arn : region + "/" + name) + query + (key == null ? "" : "#" + key);
    }

    /** What {@code GetSecretValue} takes as {@code SecretId}. */
    public String secretId() {
      return arn != null ? arn : name;
    }

    @Override
    public StoreKind kind() {
      return StoreKind.AWS_SM;
    }

    @Override
    public String display() {
      return name;
    }

    @Override
    public boolean pinned() {
      return versionId != null || (versionStage != null && !"AWSCURRENT".equals(versionStage));
    }
  }

  // ── Parsing ───────────────────────────────────────────────────────────────────────────────────────────────

  /** A Vault path segment: what KV paths and engine roles use; no {@code %}, no dot segments (checked apart). */
  Pattern VAULT_SEGMENT = Pattern.compile("[A-Za-z0-9._~@=+,:-]{1,128}");
  /** A JSON key inside a secret: printable, without the reference's own delimiters. */
  Pattern KEY = Pattern.compile("[^\\s?#/\\\\\\p{Cntrl}]{1,256}");
  Pattern GCP_PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]|[0-9]{1,20}");
  Pattern GCP_LOCATION = Pattern.compile("[a-z]+(?:-[a-z]+)*[0-9]+");
  Pattern GCP_SECRET = Pattern.compile("[A-Za-z0-9_-]{1,255}");
  Pattern GCP_VERSION = Pattern.compile("latest|[1-9][0-9]{0,18}");
  Pattern AWS_REGION = Pattern.compile("[a-z]{2}(?:-[a-z]+)+-[0-9]+");
  Pattern AWS_NAME = Pattern.compile("[A-Za-z0-9/_+=.@-]{1,512}");
  Pattern AWS_ARN = Pattern.compile("arn:(aws|aws-cn|aws-us-gov):secretsmanager:([a-z]{2}(?:-[a-z]+)+-[0-9]+):"
      + "([0-9]{12}):secret:([A-Za-z0-9/_+=.@-]{1,512})");
  Pattern AWS_STAGE = Pattern.compile("[A-Za-z0-9_.@+=,-]{1,256}");
  Pattern AWS_VERSION_ID = Pattern.compile("[A-Za-z0-9-]{32,64}");

  /**
   * Parses {@code text} into its canonical reference; both {@code ?query#key} and the draft's {@code #key?query}
   * orders are accepted.
   *
   * @throws IllegalArgumentException with what is wrong, in the tone of the app (the text names no secret value)
   */
  static SecretRef parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("Enter a reference, like vault://kv/stage/db#password.");
    }
    String t = text.strip();
    if (t.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("A reference has at most " + MAX_LENGTH + " characters.");
    }
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '%' || c > 0x7e) {
        throw new IllegalArgumentException("A reference has no spaces, percent escapes or special characters.");
      }
    }
    int sep = t.indexOf("://");
    if (sep < 0) {
      throw new IllegalArgumentException("A reference starts with vault://, gcp-sm:// or aws-sm://.");
    }
    String scheme = t.substring(0, sep);
    String rest = t.substring(sep + 3);
    int q = rest.indexOf('?');
    int h = rest.indexOf('#');
    String body;
    String query = null;
    String key = null;
    if (q >= 0 && (h < 0 || q < h)) {
      body = rest.substring(0, q);
      query = h < 0 ? rest.substring(q + 1) : rest.substring(q + 1, h);
      key = h < 0 ? null : rest.substring(h + 1);
    } else if (h >= 0) {
      body = rest.substring(0, h);
      int q2 = rest.indexOf('?', h);
      key = q2 < 0 ? rest.substring(h + 1) : rest.substring(h + 1, q2);
      query = q2 < 0 ? null : rest.substring(q2 + 1);
    } else {
      body = rest;
    }
    if (key != null && !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("The key after # can't be empty or contain spaces, ?, # or slashes.");
    }
    Map<String, String> params = query(query);
    return switch (scheme) {
      case "vault" -> vault(body, params, key);
      case "gcp-sm" -> gcp(body, params, key);
      case "aws-sm" -> aws(body, params, key);
      default -> throw new IllegalArgumentException("A reference starts with vault://, gcp-sm:// or aws-sm://.");
    };
  }

  private static Map<String, String> query(String query) {
    Map<String, String> params = new LinkedHashMap<>();
    if (query == null) {
      return params;
    }
    if (query.isEmpty()) {
      throw new IllegalArgumentException("Remove the empty ? from the reference.");
    }
    for (String pair : query.split("&", -1)) {
      int eq = pair.indexOf('=');
      if (eq <= 0 || eq == pair.length() - 1) {
        throw new IllegalArgumentException("Write the query of a reference as name=value.");
      }
      if (params.put(pair.substring(0, eq), pair.substring(eq + 1)) != null) {
        throw new IllegalArgumentException("The reference names " + pair.substring(0, eq) + " twice.");
      }
    }
    return params;
  }

  private static Vault vault(String body, Map<String, String> params, String key) {
    String path = body;
    if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
      throw new IllegalArgumentException("Write the Vault path without leading, trailing or double slashes, like "
          + "vault://kv/stage/db#password.");
    }
    String[] segments = path.split("/", -1);
    if (segments.length < 2) {
      throw new IllegalArgumentException("A Vault reference names the secrets engine and the secret, like "
          + "vault://kv/stage/db#password.");
    }
    for (String s : segments) {
      if (s.equals(".") || s.equals("..") || !VAULT_SEGMENT.matcher(s).matches()) {
        throw new IllegalArgumentException("The Vault path has a segment MCP Gateway doesn't accept: \"" + s + "\".");
      }
    }
    Integer version = null;
    for (Map.Entry<String, String> p : params.entrySet()) {
      if (!p.getKey().equals("version")) {
        throw new IllegalArgumentException("A Vault reference takes only ?version=<n>.");
      }
      try {
        version = Integer.valueOf(p.getValue());
      } catch (NumberFormatException e) {
        version = null;
      }
      if (version == null || version < 1 || !p.getValue().equals(String.valueOf(version))) {
        throw new IllegalArgumentException("The Vault version is a number from 1, like ?version=3.");
      }
    }
    return new Vault(path, version, key);
  }

  private static GcpSm gcp(String body, Map<String, String> params, String key) {
    if (!params.isEmpty()) {
      throw new IllegalArgumentException("A GCP Secret Manager reference names its version in the path, like "
          + ".../versions/4 or .../versions/latest.");
    }
    String[] s = body.split("/", -1);
    int i = 0;
    String project = null;
    String location = null;
    String secret = null;
    String version = "latest";
    if (s.length >= 4 && s[0].equals("projects")) {
      project = s[1];
      i = 2;
      if (s.length >= 6 && s[2].equals("locations")) {
        location = s[3];
        i = 4;
      }
      if (s[i].equals("secrets")) {
        secret = i + 1 < s.length ? s[i + 1] : null;
        i += 2;
        if (i < s.length) {
          if (i + 2 == s.length && s[i].equals("versions")) {
            version = s[i + 1];
          } else {
            secret = null;
          }
        }
      }
    }
    if (project == null || secret == null) {
      throw new IllegalArgumentException("Write a GCP Secret Manager reference as gcp-sm://projects/<project>/secrets/"
          + "<secret>/versions/<n or latest>.");
    }
    if (!GCP_PROJECT.matcher(project).matches()) {
      throw new IllegalArgumentException(project + " isn't a GCP project ID.");
    }
    if (location != null && !GCP_LOCATION.matcher(location).matches()) {
      throw new IllegalArgumentException(location + " isn't a GCP location, like europe-west1.");
    }
    if (!GCP_SECRET.matcher(secret).matches()) {
      throw new IllegalArgumentException(secret + " isn't a Secret Manager secret ID (letters, digits, - and _).");
    }
    if (!GCP_VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("The version of a GCP secret is a number or latest.");
    }
    return new GcpSm(project, location, secret, version, key);
  }

  private static AwsSm aws(String body, Map<String, String> params, String key) {
    String stage = null;
    String versionId = null;
    for (Map.Entry<String, String> p : params.entrySet()) {
      switch (p.getKey()) {
        case "versionStage" -> {
          if (!AWS_STAGE.matcher(p.getValue()).matches()) {
            throw new IllegalArgumentException(p.getValue() + " isn't a version stage, like AWSCURRENT.");
          }
          stage = p.getValue();
        }
        case "versionId" -> {
          if (!AWS_VERSION_ID.matcher(p.getValue()).matches()) {
            throw new IllegalArgumentException(p.getValue() + " isn't a version ID.");
          }
          versionId = p.getValue();
        }
        default -> throw new IllegalArgumentException("An AWS Secrets Manager reference takes ?versionStage= or "
            + "?versionId=.");
      }
    }
    if (stage != null && versionId != null) {
      throw new IllegalArgumentException("Name either a version stage or a version ID, not both.");
    }
    if (body.startsWith("arn:")) {
      var m = AWS_ARN.matcher(body);
      if (!m.matches()) {
        throw new IllegalArgumentException("That isn't an AWS Secrets Manager secret ARN.");
      }
      return new AwsSm(m.group(2), m.group(4), body, stage, versionId, key);
    }
    int slash = body.indexOf('/');
    if (slash <= 0 || slash == body.length() - 1) {
      throw new IllegalArgumentException("Write an AWS Secrets Manager reference as aws-sm://<region>/<name>, like "
          + "aws-sm://eu-central-1/stage/billing-api#webhook_secret.");
    }
    String region = body.substring(0, slash);
    String name = body.substring(slash + 1);
    if (!AWS_REGION.matcher(region).matches()) {
      throw new IllegalArgumentException(region + " isn't an AWS region, like eu-central-1.");
    }
    if (!AWS_NAME.matcher(name).matches() || name.startsWith("/") || name.endsWith("/") || name.contains("//")) {
      throw new IllegalArgumentException(name + " isn't an AWS Secrets Manager name (letters, digits and /_+=.@-).");
    }
    return new AwsSm(region, name, null, stage, versionId, key);
  }
}
