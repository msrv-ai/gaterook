package com.gaterook.connectors.gcp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What {@code gcp_api_read} and {@code gcp_api_change} never call, and which methods only read. These are code, not
 * policy: the person narrows further with the Cloud policy, but no setting reaches past these rules.
 *
 * <p>A method is named as its Discovery document names it: the API ({@code run}, {@code sqladmin}) and the method's
 * id without its first segment ({@code projects.locations.services.list}). The checks run on the agent's words in
 * {@code classify} and again on the method the document resolved before any request.
 */
final class ApiRules {

  /** Whole APIs these tools never call, and why or where to go instead. */
  private static final Map<String, String> DENIED_APIS = Map.of(
      "iamcredentials", "It issues access tokens, ID tokens and signatures for service accounts.",
      "sts", "It exchanges credentials for access tokens.",
      "oauth2", "It issues and inspects access tokens.",
      "cloudasset", "Use gcp_describe: it applies the Cloud policy to every resource it finds.",
      "logging", "Use logs_search and logs_tail: they keep queries inside the connection's scopes.");

  /**
   * Last segments of methods that hand out secrets, credentials, signed URLs or private data in any API: a secret
   * version's {@code access}, a Memorystore {@code getAuthString}, a VM's serial port and screenshot, signed upload
   * and download URLs, service account tokens and signatures, API key strings, repository tokens of Cloud Build,
   * ephemeral database certificates and KMS decryption and signing.
   */
  private static final Set<String> SECRET_METHODS = Set.of("access", "getAuthString", "getSerialPortOutput",
      "getScreenshot", "getGuestAttributes", "generateDownloadUrl", "generateUploadUrl", "generateAccessToken",
      "generateIdToken", "signBlob", "signJwt", "getKeyString", "fetchReadToken", "fetchReadWriteToken",
      "generateEphemeralCert", "createEphemeral", "decrypt", "asymmetricDecrypt", "asymmetricSign", "macSign",
      "rawDecrypt", "rawEncrypt", "exportKey");

  /** Words in a method's last segment that mean it deals in secrets ({@code generateToken}, {@code getPassword}). */
  private static final Pattern SECRET_WORDS = Pattern.compile("(?i).*(token|secret|password|credential|signedurl)"
      + ".*");

  /** Single methods these tools never call: they return private keys or HMAC secrets. */
  private static final Map<String, String> DENIED_METHODS = Map.of(
      "iam.projects.serviceAccounts.keys.create", "It returns the new key's private key.",
      "storage.projects.hmacKeys.create", "It returns the new HMAC key's secret.",
      "apikeys.projects.locations.keys.create", "Its operation returns the new key string.");

  /** Data planes with connectors of their own: rows, documents and time series go there, with their limits. */
  private static final List<Map.Entry<String, String>> DATA_PLANES = List.of(
      Map.entry("bigquery.jobs.query", "Use the BigQuery connector for queries and table data."),
      Map.entry("bigquery.jobs.insert", "Use the BigQuery connector for queries and table data."),
      Map.entry("bigquery.jobs.getQueryResults", "Use the BigQuery connector for queries and table data."),
      Map.entry("bigquery.tabledata.", "Use the BigQuery connector for queries and table data."),
      Map.entry("firestore.projects.databases.documents.", "Use a database connector for Firestore documents."),
      Map.entry("spanner.projects.instances.databases.sessions.", "Use the Spanner connector for Spanner data."),
      Map.entry("datastore.projects.runQuery", "Use a database connector for Datastore entities."),
      Map.entry("datastore.projects.runAggregationQuery", "Use a database connector for Datastore entities."),
      Map.entry("datastore.projects.lookup", "Use a database connector for Datastore entities."),
      Map.entry("datastore.projects.commit", "Use a database connector for Datastore entities."),
      Map.entry("monitoring.projects.timeSeries.list", "Use gcp_metrics: it keeps other projects' series out."),
      Map.entry("monitoring.projects.timeSeries.query", "Use gcp_metrics: it keeps other projects' series out."));

  /** POST methods that only read (by their last segment): permissions, IAM and organization policies, ancestry. */
  private static final Set<String> READ_POSTS = Set.of("testIamPermissions", "getIamPolicy", "getAncestry",
      "getEffectiveOrgPolicy", "getOrgPolicy", "listOrgPolicies", "listAvailableOrgPolicyConstraints");

  /** First words of method names that read, for {@code classify} before the Discovery document is known. */
  private static final Pattern READ_NAME = Pattern.compile("^(get|list|search|aggregatedList|batchGet|lookup|fetch"
      + "|query|check|retrieve|analyze|describe|testIamPermissions)([A-Z].*)?$");

  /** Standard query parameters an agent may set; every other one (key, access_token, alt=media…) is refused. */
  static final Set<String> STANDARD_PARAMS = Set.of("fields", "prettyPrint");

  /** Standard parameters that change how a request authenticates or what it returns: never accepted. */
  static final Set<String> FORBIDDEN_PARAMS = Set.of("key", "access_token", "oauth_token", "callback", "alt",
      "uploadType", "upload_protocol", "$.xgafv", "quotaUser", "userIp");

  private ApiRules() {}

  /** Why {@code api}'s method {@code key} is never called, or {@code null}. */
  static String denied(String api, String key) {
    String why = DENIED_APIS.get(api);
    if (why != null) {
      return "gcp_api tools don't call the " + api + " API. " + why;
    }
    String full = api + "." + key;
    why = DENIED_METHODS.get(full);
    if (why != null) {
      return api + " " + key + " is never called through GateRook. " + why;
    }
    for (Map.Entry<String, String> plane : DATA_PLANES) {
      if (plane.getKey().endsWith(".") ? full.startsWith(plane.getKey()) : full.equals(plane.getKey())) {
        return api + " " + key + " reads or writes data that has its own connector. " + plane.getValue();
      }
    }
    String last = last(key);
    if (SECRET_METHODS.contains(last) || SECRET_WORDS.matcher(last).matches()) {
      return api + " " + key + " returns secrets, credentials or signed URLs, which GateRook never hands to agents.";
    }
    return null;
  }

  /** Whether a method of this HTTP verb and name only reads: GET, or one of the read-only POSTs. */
  static boolean reads(String httpMethod, String key) {
    return "GET".equals(httpMethod) || "POST".equals(httpMethod) && READ_POSTS.contains(last(key));
  }

  /** Whether the name looks like a read: {@code classify}'s guess while the document isn't known yet. */
  static boolean looksLikeRead(String key) {
    return READ_NAME.matcher(last(key)).matches();
  }

  /** {@code list} of {@code projects.locations.services.list}. */
  static String last(String key) {
    int dot = key.lastIndexOf('.');
    return dot < 0 ? key : key.substring(dot + 1);
  }

  /** Whether the method reads a collection (its results are filtered by the policy), not one resource. */
  static boolean collection(String key) {
    String last = last(key);
    return last.startsWith("list") || last.startsWith("aggregatedList") || last.startsWith("search");
  }
}
