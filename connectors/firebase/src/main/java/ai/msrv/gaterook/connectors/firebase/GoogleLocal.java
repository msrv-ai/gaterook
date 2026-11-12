package ai.msrv.gaterook.connectors.firebase;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Google project a form most likely means, as this computer and the form say (local suggestions): the project of a
 * pasted service account key, gcloud's project, the project of the service account to impersonate. Names only: nothing
 * goes over the network and no command runs. The only secret read is the form's service account key, parsed in memory:
 * for local suggestions the core hands over a typed key only.
 */
final class GoogleLocal {

  static final String KEY_FIELD = "/credentials/serviceAccountKey";
  static final String NO_SIGN_IN = "No Google sign-in on this computer. Run gcloud auth application-default login, "
      + "or use a service account key.";
  static final String NOT_A_KEY = "The service account key isn't the JSON of a key file: paste the whole file that "
      + "Google downloaded.";
  static final String KEY_SOURCE = "the service account key";
  static final String GCLOUD_SOURCE = "your gcloud configuration";
  private static final Pattern PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
  private static final Pattern SERVICE_ACCOUNT =
      Pattern.compile("[a-z0-9-]+@([a-z][a-z0-9-]{4,28}[a-z0-9])\\.iam\\.gserviceaccount\\.com");

  private GoogleLocal() {}

  /**
   * The projects the form may mean.
   *
   * @param value   the one to fill in: the key's with a service account key, else gcloud's; {@code null} without one
   * @param source  where the options come from, as the hint under the field names it
   * @param options every project found, {@code value} first
   */
  record Projects(String value, String source, List<Option> options) {

    /** These projects and {@code more} after them, found in {@code where} (a service's own list). */
    Projects plus(String where, List<Option> more) {
      Map<String, Option> all = new LinkedHashMap<>();
      options.forEach(o -> all.put(o.value(), o));
      more.forEach(o -> all.putIfAbsent(o.value(), o));
      return new Projects(value, options.isEmpty() && !more.isEmpty() ? where : source, List.copyOf(all.values()));
    }
  }

  /** The projects, and a note in {@code out} when the form can't sign in on this computer yet. */
  static Projects projects(ConnectionConfig cfg, SecretResolver secrets, CloudLocal local, Suggestions.Builder out) {
    boolean key = "serviceAccountKey".equals(cfg.string("/credentials/source"));
    Map<String, Option> found = new LinkedHashMap<>();
    List<String> sources = new ArrayList<>();
    String value = null;
    if (key) {
      String project = keyProject(secrets, out);
      if (project != null) {
        value = project;
        found.put(project, new Option(project, null, "service account key"));
        sources.add(KEY_SOURCE);
      }
    } else if (!local.gcpSignedIn()) {
      out.note(NO_SIGN_IN);
    }
    String gcloud = project(local.gcpProject());
    if (gcloud != null && found.putIfAbsent(gcloud, new Option(gcloud, null, "gcloud")) == null) {
      sources.add(GCLOUD_SOURCE);
      if (!key) {
        value = gcloud;
      }
    }
    String impersonate = cfg.string("/credentials/impersonate");
    Matcher sa = impersonate == null ? null : SERVICE_ACCOUNT.matcher(impersonate.strip());
    if (sa != null && sa.matches() && found.putIfAbsent(sa.group(1), new Option(sa.group(1), null,
        "project of " + impersonate.strip())) == null) {
      sources.add("the service account to impersonate");
    }
    return new Projects(value, sources.isEmpty() ? GCLOUD_SOURCE : sources.getFirst(), List.copyOf(found.values()));
  }

  /** The projects for {@code pointer}, each with {@code prefix} ({@code projects/} for a resource scope). */
  static void suggest(Suggestions.Builder out, Projects projects, String pointer, String prefix) {
    List<Option> options = projects.options().stream()
        .map(o -> new Option(prefix + o.value(), o.label(), o.detail())).toList();
    out.value(pointer, projects.source(), projects.value() == null ? null : prefix + projects.value(), options);
  }

  /**
   * A service suggestion that failed, as a note: what Google or the sign-in said, unless it only repeats that this
   * computer has no Google sign-in, which the local note already says.
   */
  static void failed(Suggestions.Builder out, ConnectorException e) {
    String message = e.getMessage();
    if (message != null && message.startsWith("No Google sign-in on this computer")
        && out.build().notes().contains(NO_SIGN_IN)) {
      return;
    }
    out.note(message);
  }

  /** The project of a key typed into the form ({@code project_id}), or {@code null}; a note when it isn't a key. */
  private static String keyProject(SecretResolver secrets, Suggestions.Builder out) {
    if (secrets == null || !secrets.isSet(KEY_FIELD)) {
      return null;
    }
    try (SecretResolver.Secret secret = secrets.secret(KEY_FIELD)) {
      String project = project(CloudLocal.keyProject(secret.unsafeString()));
      if (project == null) {
        out.note(NOT_A_KEY);
      }
      return project;
    } catch (ConnectorException e) {
      // A key that can't be read now (from a store that doesn't answer) names no project.
      return null;
    }
  }

  /** A project ID as the forms take it, or {@code null}: a number or anything else isn't offered. */
  private static String project(String value) {
    return value != null && PROJECT.matcher(value.strip()).matches() ? value.strip() : null;
  }
}
