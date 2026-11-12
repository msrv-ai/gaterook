package ai.msrv.gaterook.connectors.cloudauth;

import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import software.amazon.awssdk.profiles.Profile;
import software.amazon.awssdk.profiles.ProfileFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the command-line tools of this computer already know about the clouds, for the setup forms' local suggestions
 * ({@link ai.msrv.gaterook.connector.api.Connector#suggest}): AWS profiles and their regions, gcloud's project, the
 * Azure CLI's subscriptions and tenant. Names and identifiers only: no key, token or secret is read into a suggestion,
 * nothing runs and nothing goes over the network.
 */
public final class CloudLocal {

  /** Where the files are read: the tests' {@link CloudEndpoints} or this computer's defaults. */
  private final CloudEndpoints endpoints;
  private final Function<String, String> env;
  private final Path home;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The AWS commercial, GovCloud and China regions with their names, as the regions list of the AWS console shows
   * them. A region not listed here can still be typed.
   */
  public static final Map<String, String> AWS_REGIONS = regions(
      "us-east-1", "US East (N. Virginia)", "us-east-2", "US East (Ohio)", "us-west-1", "US West (N. California)",
      "us-west-2", "US West (Oregon)", "af-south-1", "Africa (Cape Town)", "ap-east-1", "Asia Pacific (Hong Kong)",
      "ap-east-2", "Asia Pacific (Taipei)", "ap-south-1", "Asia Pacific (Mumbai)", "ap-south-2",
      "Asia Pacific (Hyderabad)", "ap-southeast-1", "Asia Pacific (Singapore)", "ap-southeast-2",
      "Asia Pacific (Sydney)", "ap-southeast-3", "Asia Pacific (Jakarta)", "ap-southeast-4",
      "Asia Pacific (Melbourne)", "ap-southeast-5", "Asia Pacific (Malaysia)", "ap-southeast-7",
      "Asia Pacific (Thailand)", "ap-northeast-1", "Asia Pacific (Tokyo)", "ap-northeast-2", "Asia Pacific (Seoul)",
      "ap-northeast-3", "Asia Pacific (Osaka)", "ca-central-1", "Canada (Central)", "ca-west-1", "Canada West (Calgary)",
      "eu-central-1", "Europe (Frankfurt)", "eu-central-2", "Europe (Zurich)", "eu-west-1", "Europe (Ireland)",
      "eu-west-2", "Europe (London)", "eu-west-3", "Europe (Paris)", "eu-south-1", "Europe (Milan)", "eu-south-2",
      "Europe (Spain)", "eu-north-1", "Europe (Stockholm)", "il-central-1", "Israel (Tel Aviv)", "me-south-1",
      "Middle East (Bahrain)", "me-central-1", "Middle East (UAE)", "mx-central-1", "Mexico (Central)", "sa-east-1",
      "South America (São Paulo)", "us-gov-east-1", "AWS GovCloud (US-East)", "us-gov-west-1",
      "AWS GovCloud (US-West)", "cn-north-1", "China (Beijing)", "cn-northwest-1", "China (Ningxia)");

  public CloudLocal(CloudEndpoints endpoints) {
    this(endpoints, System::getenv, Path.of(System.getProperty("user.home")));
  }

  /** For tests: another environment and home folder. */
  public CloudLocal(CloudEndpoints endpoints, Function<String, String> env, Path home) {
    this.endpoints = endpoints == null ? CloudEndpoints.PRODUCTION : endpoints;
    this.env = env;
    this.home = home;
  }

  // ── AWS ─────────────────────────────────────────────────────────────────────────────────────────────────────

  /** One profile of {@code ~/.aws/config} or {@code ~/.aws/credentials}: its name and region, if it sets one. */
  public record AwsProfile(String name, String region) {}

  /** The profiles of this computer, the one {@code AWS_PROFILE} names (else {@code default}) first. */
  public List<AwsProfile> awsProfiles() {
    ProfileFile file;
    try {
      file = awsFile();
    } catch (RuntimeException e) {
      return List.of();
    }
    List<AwsProfile> out = new ArrayList<>();
    for (Map.Entry<String, Profile> e : file.profiles().entrySet()) {
      out.add(new AwsProfile(e.getKey(), e.getValue().property("region").filter(r -> !r.isBlank()).orElse(null)));
    }
    String preferred = Optional.ofNullable(env.apply("AWS_PROFILE")).filter(p -> !p.isBlank()).orElse("default");
    out.sort((a, b) -> Boolean.compare(!a.name().equals(preferred), !b.name().equals(preferred)));
    return out;
  }

  /** The region the AWS CLI would use with {@code profile}: the profile's, else {@code AWS_REGION}'s. */
  public String awsRegion(String profile) {
    String name = profile != null && !profile.isBlank() ? profile
        : Optional.ofNullable(env.apply("AWS_PROFILE")).filter(p -> !p.isBlank()).orElse("default");
    for (AwsProfile p : awsProfiles()) {
      if (p.name().equals(name) && p.region() != null) {
        return p.region();
      }
    }
    for (String variable : List.of("AWS_REGION", "AWS_DEFAULT_REGION")) {
      String region = env.apply(variable);
      if (region != null && !region.isBlank()) {
        return region.strip();
      }
    }
    return null;
  }

  /** Every known region, the ones the profiles of this computer use first. */
  public List<Option> awsRegionOptions() {
    List<Option> out = new ArrayList<>();
    List<String> used = awsProfiles().stream().map(AwsProfile::region).filter(r -> r != null).distinct().toList();
    for (String region : used) {
      out.add(new Option(region, null, AWS_REGIONS.getOrDefault(region, "used by a profile")));
    }
    AWS_REGIONS.forEach((region, name) -> {
      if (!used.contains(region)) {
        out.add(new Option(region, null, name));
      }
    });
    return out;
  }

  /**
   * The usual AWS suggestions of a form: the profiles for {@code profilePointer} (with the region each sets), the
   * regions for each of {@code regionPointers} with the chosen profile's region filled in.
   *
   * @param profile the profile the form names now, or {@code null}
   * @param list whether the region fields hold lists ({@code regions}: the region goes in as the first item)
   */
  public Suggestions.Builder aws(Suggestions.Builder out, String profilePointer, String profile, boolean list,
      String... regionPointers) {
    List<AwsProfile> profiles = awsProfiles();
    if (profilePointer != null && !profiles.isEmpty()) {
      String preferred = profiles.get(0).name();
      out.value(profilePointer, "~/.aws/config", profile == null || profile.isBlank() ? preferred : null,
          profiles.stream().map(p -> new Option(p.name(), null, p.region())).toList());
    }
    // Without a profile named yet, the region of the one the AWS CLI would pick, else the first with a region.
    String region = awsRegion(profile);
    if (region == null && (profile == null || profile.isBlank())) {
      region = profiles.stream().map(AwsProfile::region).filter(r -> r != null).findFirst().orElse(null);
    }
    for (String pointer : regionPointers) {
      out.value(pointer, region != null ? "~/.aws/config" : "the AWS regions", region, awsRegionOptions());
    }
    return out;
  }

  private ProfileFile awsFile() {
    if (endpoints.awsConfigFile() == null && endpoints.awsCredentialsFile() == null) {
      return ProfileFile.defaultProfileFile();
    }
    ProfileFile.Aggregator aggregator = ProfileFile.aggregator();
    if (endpoints.awsConfigFile() != null && Files.exists(endpoints.awsConfigFile())) {
      aggregator.addFile(ProfileFile.builder().content(endpoints.awsConfigFile())
          .type(ProfileFile.Type.CONFIGURATION).build());
    }
    if (endpoints.awsCredentialsFile() != null && Files.exists(endpoints.awsCredentialsFile())) {
      aggregator.addFile(ProfileFile.builder().content(endpoints.awsCredentialsFile())
          .type(ProfileFile.Type.CREDENTIALS).build());
    }
    return aggregator.build();
  }

  // ── Google Cloud ────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The project gcloud works in: {@code GOOGLE_CLOUD_PROJECT}, the active gcloud configuration's {@code [core] project},
   * or the quota project of the application default credentials; {@code null} without one.
   */
  public String gcpProject() {
    for (String variable : List.of("GOOGLE_CLOUD_PROJECT", "CLOUDSDK_CORE_PROJECT")) {
      String v = env.apply(variable);
      if (v != null && !v.isBlank()) {
        return v.strip();
      }
    }
    Path dir = gcloudDir();
    String active = read(dir.resolve("active_config")).map(String::strip).filter(s -> !s.isEmpty())
        .orElse("default");
    String project = read(dir.resolve("configurations").resolve("config_" + active))
        .map(text -> ini(text, "core", "project")).orElse(null);
    if (project != null) {
      return project;
    }
    Path adc = endpoints.gcpAdcFile() != null ? endpoints.gcpAdcFile()
        : dir.resolve("application_default_credentials.json");
    return read(adc).map(text -> {
      try {
        String quota = JSON.readTree(text).path("quota_project_id").asString("");
        return quota.isBlank() ? null : quota;
      } catch (RuntimeException e) {
        return null;
      }
    }).orElse(null);
  }

  /** Whether {@code gcloud auth application-default login} ran on this computer (its file exists). */
  public boolean gcpSignedIn() {
    Path adc = endpoints.gcpAdcFile() != null ? endpoints.gcpAdcFile()
        : gcloudDir().resolve("application_default_credentials.json");
    return Files.isRegularFile(adc) || present(env.apply("GOOGLE_APPLICATION_CREDENTIALS"));
  }

  /** The project a pasted service account key belongs to ({@code project_id}), or {@code null}. */
  public static String keyProject(String serviceAccountKey) {
    if (serviceAccountKey == null || serviceAccountKey.isBlank()) {
      return null;
    }
    try {
      String project = JSON.readTree(serviceAccountKey).path("project_id").asString("");
      return project.isBlank() ? null : project;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private Path gcloudDir() {
    String configured = env.apply("CLOUDSDK_CONFIG");
    if (present(configured)) {
      return Path.of(configured);
    }
    String appData = env.apply("APPDATA");
    if (present(appData) && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
      return Path.of(appData, "gcloud");
    }
    return home.resolve(".config").resolve("gcloud");
  }

  // ── Azure ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** One subscription the Azure CLI signed in to. */
  public record AzureSubscription(String id, String name, String tenantId, boolean isDefault, String cloud) {}

  /** The subscriptions of {@code az login} ({@code ~/.azure/azureProfile.json}), the default one first. */
  public List<AzureSubscription> azureSubscriptions() {
    String dir = env.apply("AZURE_CONFIG_DIR");
    Path file = (present(dir) ? Path.of(dir) : home.resolve(".azure")).resolve("azureProfile.json");
    Optional<String> text = read(file);
    if (text.isEmpty()) {
      return List.of();
    }
    List<AzureSubscription> out = new ArrayList<>();
    try {
      // The Azure CLI writes this file with a byte order mark.
      JsonNode root = JSON.readTree(text.get().replace("﻿", ""));
      for (JsonNode s : root.path("subscriptions")) {
        String id = s.path("id").asString("");
        if (!id.isBlank() && !"Disabled".equalsIgnoreCase(s.path("state").asString(""))) {
          out.add(new AzureSubscription(id, s.path("name").asString(null), s.path("tenantId").asString(null),
              s.path("isDefault").asBoolean(false), s.path("environmentName").asString(null)));
        }
      }
    } catch (RuntimeException e) {
      return List.of();
    }
    out.sort((a, b) -> Boolean.compare(!a.isDefault(), !b.isDefault()));
    return out;
  }

  /** The subscriptions as options: the id, labelled by its name, with its tenant. */
  public List<Option> azureSubscriptionOptions() {
    return azureSubscriptions().stream().map(s -> new Option(s.id(),
        s.name() == null ? null : s.name() + (s.isDefault() ? " (default)" : ""),
        s.tenantId() == null ? null : "tenant " + s.tenantId())).toList();
  }

  /** The tenants of those subscriptions, the default subscription's first. */
  public List<Option> azureTenantOptions() {
    Map<String, List<String>> tenants = new LinkedHashMap<>();
    for (AzureSubscription s : azureSubscriptions()) {
      if (s.tenantId() != null) {
        tenants.computeIfAbsent(s.tenantId(), k -> new ArrayList<>()).add(s.name() == null ? s.id() : s.name());
      }
    }
    List<Option> out = new ArrayList<>();
    tenants.forEach((tenant, names) -> out.add(new Option(tenant, null, String.join(", ", names))));
    return out;
  }

  // ── Files ───────────────────────────────────────────────────────────────────────────────────────────────────

  private static Optional<String> read(Path file) {
    try {
      if (file == null || !Files.isRegularFile(file) || Files.size(file) > 1 << 20) {
        return Optional.empty();
      }
      return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /** {@code key} of {@code [section]} in an INI text, or {@code null}. */
  static String ini(String text, String section, String key) {
    String current = null;
    for (String raw : text.split("\\R")) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
        continue;
      }
      if (line.startsWith("[") && line.endsWith("]")) {
        current = line.substring(1, line.length() - 1).strip();
        continue;
      }
      int eq = line.indexOf('=');
      if (eq > 0 && section.equals(current) && line.substring(0, eq).strip().equals(key)) {
        String value = line.substring(eq + 1).strip();
        return value.isEmpty() ? null : value;
      }
    }
    return null;
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  private static Map<String, String> regions(String... pairs) {
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      out.put(pairs[i], pairs[i + 1]);
    }
    return java.util.Collections.unmodifiableMap(out);
  }
}
