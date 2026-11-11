package ai.msrv.gaterook.connectors.bigquery;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import ai.msrv.gaterook.connectors.support.Glob;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The settings of one BigQuery connection ({@code config.schema.json}).
 *
 * @param project        the project that runs and pays for the jobs
 * @param location       the jobs' location ({@code US}, {@code EU}, {@code europe-west1}), or {@code null}: BigQuery's
 * @param datasets       globs of {@code dataset} (in {@code project}) or {@code project.dataset}; empty: every dataset
 *                       the credentials may read
 * @param maxBytesBilled the most bytes one statement may process ({@code maximumBytesBilled})
 */
record BigQuerySettings(String project, String location, List<String> datasets, long maxBytesBilled,
    GcpSpec credentials) {

  static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024 * 1024;
  static final Pattern PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]|[a-z0-9.-]{1,63}:[a-z][a-z0-9-]{4,28}"
      + "[a-z0-9]");
  static final Pattern DATASET = Pattern.compile("[A-Za-z0-9_]{1,1024}");
  static final Pattern TABLE = Pattern.compile("[\\p{L}\\p{M}\\p{N}\\p{Pc}\\p{Pd} $*-]{1,1024}");
  private static final Pattern LOCATION = Pattern.compile("[A-Za-z0-9-]{2,40}");

  static BigQuerySettings of(ConnectionConfig cfg) throws ConnectorException {
    String project = cfg.string("/project");
    if (project == null || !PROJECT.matcher(project).matches()) {
      throw ConnectorException.badInput("Enter the Google Cloud project that runs the queries, such as acme-analytics.");
    }
    String location = cfg.string("/location");
    if (location != null && location.isBlank()) {
      location = null;
    }
    if (location != null && !LOCATION.matcher(location).matches()) {
      throw ConnectorException.badInput(location + " isn't a BigQuery location such as US, EU or europe-west1.");
    }
    GcpSpec spec;
    try {
      spec = GcpSpec.fromConfig(cfg.config(), "/credentials");
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The Google credentials of " + cfg.name() + " aren't complete: "
          + e.getMessage() + ".");
    }
    return new BigQuerySettings(project, location, List.copyOf(cfg.strings("/datasets")), maxBytes(cfg), spec);
  }

  /** The connection's limit of one statement: {@code advanced.maxMbBilled} MiB, at least 10 MiB. */
  static long maxBytes(ConnectionConfig cfg) {
    long mb = cfg.config().at("/advanced/maxMbBilled").asLong(DEFAULT_MAX_BYTES >> 20);
    return Math.max(10L, Math.min(mb, 100L << 20)) << 20;
  }

  /** Whether agents may use the dataset {@code project.dataset}: on the list, or any when the list is empty. */
  boolean allows(String datasetProject, String dataset) {
    if (datasets.isEmpty()) {
      return true;
    }
    for (String entry : datasets) {
      // A dataset has no dot; a domain-scoped project (example.com:p) may.
      int dot = entry.lastIndexOf('.');
      String p = dot < 0 ? project : entry.substring(0, dot);
      String d = dot < 0 ? entry : entry.substring(dot + 1);
      if (Glob.text(p.toLowerCase(Locale.ROOT)).matches(datasetProject.toLowerCase(Locale.ROOT))
          && Glob.text(d).matches(dataset)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code ds.t}, {@code p.ds.t} or {@code `p.ds.t`} as {@code [project, dataset, table]}, read from the right: a
   * table and a dataset have no dot, a domain-scoped project ({@code example.com:p}) may. {@code null} if not one.
   */
  String[] table(String written) {
    if (written == null) {
      return null;
    }
    String plain = written.replace("`", "");
    int dot = plain.lastIndexOf('.');
    if (dot <= 0 || dot == plain.length() - 1) {
      return null;
    }
    String[] d = dataset(plain.substring(0, dot));
    return d == null ? null : new String[] {d[0], d[1], plain.substring(dot + 1)};
  }

  /** {@code ds} or {@code p.ds} as {@code [project, dataset]}, read from the right; {@code null} if not one. */
  String[] dataset(String written) {
    if (written == null) {
      return null;
    }
    String plain = written.replace("`", "");
    int dot = plain.lastIndexOf('.');
    if (dot < 0) {
      return plain.isEmpty() ? null : new String[] {project, plain};
    }
    if (dot == 0 || dot == plain.length() - 1) {
      return null;
    }
    return new String[] {plain.substring(0, dot), plain.substring(dot + 1)};
  }
}
