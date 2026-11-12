package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Help for the GCP Cloud Logging form. On this computer: {@code projects/<id>} of gcloud's project for the Google
 * sign-in, of a pasted service account key's {@code project_id} for a key, and the project of the service account to
 * impersonate; whether gcloud signed in at all. Signed in: the log views of the projects named, or while none is named
 * of those projects (a log view is the tightest scope). Listing every project stays with the form's project discovery
 * ({@code x-project-selection}, Load projects).
 */
final class GcpLoggingSuggestions {

  static final int MAX_PROJECTS = 3;
  static final int MAX_BUCKETS = 10;
  static final String KEY = "/credentials/serviceAccountKey";
  /** {@code name@project.iam.gserviceaccount.com}: the project a service account lives in. */
  private static final Pattern ACCOUNT = Pattern.compile("[a-z0-9-]+@([a-z][a-z0-9-]{4,28}[a-z0-9])\\.iam\\."
      + "gserviceaccount\\.com");
  /** {@code projects/<p>/locations/<l>/buckets/<b>}, as the form's resource names allow it. */
  private static final Pattern BUCKET = Pattern.compile("projects/([A-Za-z0-9._:-]+)/locations/([a-z0-9-]+)/buckets/"
      + "([A-Za-z0-9_-]+)");

  private GcpLoggingSuggestions() {}

  /** A project this computer or the form names, with why ({@code gcloud's project}) and where it comes from. */
  private record Known(String resource, String detail, String source) {}

  static void local(ConnectionConfig cfg, SecretResolver secrets, CloudLocal local, Suggestions.Builder out) {
    JsonNode c = cfg.config();
    String source = Optional.ofNullable(LogsSuggestions.text(c, "/credentials/source")).orElse("adc");
    if (source.equals("serviceAccountKey")) {
      String key = typedKey(secrets);
      if (key != null && CloudLocal.keyProject(key) == null) {
        out.note("This isn't a service account key: paste the whole JSON key file, with its project_id.");
      }
    } else if (!local.gcpSignedIn()) {
      out.note("Nobody is signed in to Google on this computer: run gcloud auth application-default login, or "
          + "choose a service account key.");
    }
    List<Known> known = known(c, secrets, local);
    if (known.isEmpty()) {
      return;
    }
    // The sign-in's own project is the obvious scope; the impersonated account's is only offered.
    Known first = known.getFirst();
    String value = first.source() == null ? null : first.resource();
    out.value("/resourceNames", first.source() != null ? first.source() : "the service account", value,
        known.stream().map(k -> Option.of(k.resource(), k.detail())).toList());
  }

  /**
   * Signed in as Test connection does: the log buckets and views of the projects named (at most
   * {@value #MAX_PROJECTS} projects of {@value #MAX_BUCKETS} buckets each), offered as tighter scopes. While no project
   * is named yet ({@code x-suggest}), those of this computer and the form, offered themselves too.
   */
  static void service(ConnectionConfig cfg, SecretResolver secrets, CloudLocal local, Backend.Call call,
      Suggestions.Builder out) throws ConnectorException {
    JsonNode c = cfg.config();
    GcpSpec spec = GcpSpec.fromConfig(c, "/credentials");
    List<String> names = LogsSuggestions.strings(c, "/resourceNames").stream()
        .filter(n -> Settings.RESOURCE.matcher(n).matches()).toList();
    Set<String> projects = new LinkedHashSet<>();
    for (String n : names) {
      if (n.startsWith("projects/") && !n.contains("/locations/") && projects.size() < MAX_PROJECTS) {
        projects.add(n.substring("projects/".length()));
      }
    }
    List<Option> options = new ArrayList<>();
    if (names.isEmpty()) {
      for (Known k : known(c, secrets, local)) {
        if (projects.size() < MAX_PROJECTS) {
          projects.add(k.resource().substring("projects/".length()));
          options.add(Option.of(k.resource(), k.detail()));
        }
      }
    }
    if (projects.isEmpty()) {
      if (names.isEmpty()) {
        out.note("Choose Load projects to list the projects this sign-in may read, or type one.");
      }
      return;
    }
    GcpLoggingBackend logging = new GcpLoggingBackend(new Settings.Gcp(names, spec, null, 30), call);
    int views = 0;
    boolean complete = true;
    for (String project : projects) {
      JsonNode buckets = logging.get("/v2/projects/" + project + "/locations/-/buckets?pageSize=50", "Cloud Logging");
      complete &= buckets.path("nextPageToken").asString("").isEmpty();
      int seen = 0;
      for (JsonNode bucket : buckets.path("buckets")) {
        Matcher b = BUCKET.matcher(bucket.path("name").asString(""));
        if (!b.matches() || !bucket.path("lifecycleState").asString("ACTIVE").equals("ACTIVE")) {
          continue;
        }
        if (++seen > MAX_BUCKETS || call.ctx().remaining().compareTo(Duration.ofSeconds(2)) < 0) {
          complete = false;
          break;
        }
        JsonNode list = logging.get("/v2/" + b.group() + "/views?pageSize=50", "Cloud Logging");
        complete &= list.path("nextPageToken").asString("").isEmpty();
        for (JsonNode view : list.path("views")) {
          String name = view.path("name").asString("");
          if (Settings.RESOURCE.matcher(name).matches() && !names.contains(name)) {
            options.add(Option.of(name, "bucket " + b.group(3) + " in " + b.group(2)));
            views++;
          }
        }
      }
    }
    out.field("/resourceNames", new Suggestions.Field(options, null, "Cloud Logging", complete));
    String where = String.join(", ", projects.stream().map(p -> "projects/" + p).toList());
    String who = "Signed in to Cloud Logging " + (spec.impersonate() != null ? "as " + spec.impersonate()
        : spec.source() == GcpSpec.Source.ADC ? "with the Google sign-in on this computer"
        : "with the service account key");
    out.note(views == 0 ? who + ": " + where + " has no other log views." : who + ": "
        + LogsSuggestions.count(views, "log view", "log views") + " in " + where + ". A log view is the tightest "
        + "scope.");
  }

  /**
   * The projects this computer and the form name: the key's ({@code project_id}) for a key, gcloud's for the Google
   * sign-in (both with where they come from), then the impersonated service account's.
   */
  private static List<Known> known(JsonNode c, SecretResolver secrets, CloudLocal local) {
    String source = Optional.ofNullable(LogsSuggestions.text(c, "/credentials/source")).orElse("adc");
    Set<String> seen = new LinkedHashSet<>();
    List<Known> out = new ArrayList<>();
    if (source.equals("serviceAccountKey")) {
      String key = typedKey(secrets);
      String project = key == null ? null : resource(CloudLocal.keyProject(key));
      if (project != null && seen.add(project)) {
        out.add(new Known(project, "the key's project", "the service account key"));
      }
    } else {
      String project = resource(local.gcpProject());
      if (project != null && seen.add(project)) {
        out.add(new Known(project, "gcloud's project", "your gcloud configuration"));
      }
    }
    String impersonate = LogsSuggestions.text(c, "/credentials/impersonate");
    Matcher account = impersonate == null ? null : ACCOUNT.matcher(impersonate);
    if (account != null && account.matches()) {
      String own = resource(account.group(1));
      if (own != null && seen.add(own)) {
        out.add(new Known(own, "the impersonated service account's project", null));
      }
    }
    return out;
  }

  /** The key the form holds, parsed in memory only; {@code null} without one. */
  private static String typedKey(SecretResolver secrets) {
    if (secrets == null || !secrets.isSet(KEY)) {
      return null;
    }
    try (SecretResolver.Secret secret = secrets.secret(KEY)) {
      return secret.unsafeString();
    } catch (ConnectorException e) {
      return null;
    }
  }

  /** {@code projects/<id>} when the form takes it as a resource name, else {@code null}. */
  private static String resource(String project) {
    if (project == null || project.isBlank()) {
      return null;
    }
    String r = "projects/" + project.strip();
    return Settings.RESOURCE.matcher(r).matches() ? r : null;
  }
}
