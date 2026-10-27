package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * An AWS connection as its form writes it (report 09 §4): the regions an agent may read, the account (typed by a
 * person or found by Test connection), a display alias and the credentials — an access key or a profile of this
 * computer, usually assuming a role with an ExternalId and the gateway's read-only session policy. Nothing here is a
 * secret: the secret half of an access key is a secret field the call resolves.
 *
 * @param regions        the connection's regions; the first is the default of every call and the STS region
 * @param accountId      the account the person expects ({@code accountId}); calls and tests refuse another one
 * @param accountAlias   display only ({@code acme-stage})
 * @param knownAccount   the account Test connection found ({@code metadata.account}), or {@code null}
 * @param credentials    the credentials, with the STS region of the first region
 * @param sessionPolicy  {@code true}: AssumeRole carries the generated read-only session policy
 */
record Settings(List<String> regions, String accountId, String accountAlias, String knownAccount, AwsSpec credentials,
    boolean sessionPolicy) {

  static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d+$");
  static final Pattern ACCOUNT = Pattern.compile("^\\d{12}$");

  /** Settings that can't be used; every call of the connection is denied with this message. */
  static final class InvalidSettingsException extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidSettingsException(String message) {
      super(message);
    }
  }

  Settings {
    regions = List.copyOf(regions);
  }

  static Settings parse(ConnectionConfig cfg) throws InvalidSettingsException {
    JsonNode c = cfg.config();
    if (c == null || !c.isObject()) {
      throw new InvalidSettingsException("The connection has no settings.");
    }
    Set<String> regions = new LinkedHashSet<>();
    for (JsonNode r : c.path("regions")) {
      String region = r.asString("").strip();
      if (!REGION.matcher(region).matches()) {
        throw new InvalidSettingsException("\"" + abbreviate(region) + "\" isn't an AWS region (eu-central-1).");
      }
      regions.add(region);
    }
    if (regions.isEmpty()) {
      throw new InvalidSettingsException("Add at least one region under Regions.");
    }
    String accountId = text(c, "accountId");
    if (accountId != null && !ACCOUNT.matcher(accountId).matches()) {
      throw new InvalidSettingsException("The account ID has 12 digits (412398765012).");
    }
    String alias = text(c, "accountAlias");
    AwsSpec spec;
    try {
      spec = AwsSpec.fromConfig(c, "/credentials", regions.iterator().next());
    } catch (IllegalArgumentException e) {
      throw new InvalidSettingsException(e.getMessage());
    }
    String roleAccount = accountOf(spec.roleArn());
    if (accountId != null && roleAccount != null && !roleAccount.equals(accountId)) {
      throw new InvalidSettingsException("The role " + spec.roleArn() + " is in account " + roleAccount
          + ", but the connection says " + accountId + ". Fix Account ID or Role ARN.");
    }
    String mode = c.path("credentials").path("sessionPolicy").asString("generated");
    if (!mode.equals("generated") && !mode.equals("none")) {
      throw new InvalidSettingsException("Session policy is generated or none.");
    }
    if (mode.equals("none") && cfg.env() == Env.PROD) {
      // The core refuses to save this (x-forbidden-env-values); a row changed behind its back still doesn't run.
      throw new InvalidSettingsException("Prod connections always carry the gateway's read-only session policy.");
    }
    if (spec.roleArn() == null && cfg.env() == Env.PROD) {
      // §9 "Stricter production rules": no AWS without the session policy on prod — and only an assumed role carries one; a key's
      // or profile's own permissions would apply as they are.
      throw new InvalidSettingsException("Prod connections read through a role: add Role ARN. AWS enforces the "
          + "gateway's read-only session policy only on an assumed role.");
    }
    String known = null;
    if (cfg.metadata() != null && cfg.metadata().path("account").isString()) {
      String a = cfg.metadata().path("account").asString();
      known = ACCOUNT.matcher(a).matches() ? a : null;
    }
    return new Settings(new ArrayList<>(regions), accountId, alias, known, spec, mode.equals("generated"));
  }

  String firstRegion() {
    return regions.getFirst();
  }

  boolean hasRegion(String region) {
    return regions.contains(region);
  }

  /** The account the calls read: the role's, the configured one, else what Test connection found; or {@code null}. */
  String account() {
    String fromRole = accountOf(credentials.roleArn());
    if (fromRole != null) {
      return fromRole;
    }
    return accountId != null ? accountId : knownAccount;
  }

  /**
   * The account part of the shared quota keys ({@code aws:<account>:<region>:<api>}): the account when known, else
   * the key or profile itself (not secrets: the access key ID is public, the secret half never appears here).
   */
  String quotaAccount() {
    String a = account();
    if (a != null) {
      return a;
    }
    String own = credentials.source() == AwsSpec.Source.PROFILE ? "profile-" + credentials.profile()
        : "key-" + credentials.accessKeyId();
    return own.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
  }

  /** Whether AssumeRole really carries the generated session policy: it needs a role and the option on. */
  boolean enforcedByAws() {
    return sessionPolicy && credentials.roleArn() != null;
  }

  /** {@code eu-central-1 · 412398765012 acme-stage}: where the connection reads, for texts. */
  String target() {
    StringBuilder t = new StringBuilder(regions.size() == 1 ? firstRegion()
        : firstRegion() + " +" + (regions.size() - 1));
    String a = account();
    if (a != null || accountAlias != null) {
      t.append(" · ");
      if (a != null) {
        t.append(a);
      }
      if (accountAlias != null) {
        t.append(a != null ? " " : "").append(accountAlias);
      }
    }
    return t.toString();
  }

  /** The partition of a region: {@code aws}, {@code aws-cn} or {@code aws-us-gov}. */
  static String partition(String region) {
    if (region.startsWith("cn-")) {
      return "aws-cn";
    }
    if (region.startsWith("us-gov-")) {
      return "aws-us-gov";
    }
    return "aws";
  }

  /** The DNS suffix of the AWS endpoints in {@code region}. */
  static String dnsSuffix(String region) {
    return region.startsWith("cn-") ? "amazonaws.com.cn" : "amazonaws.com";
  }

  /** The account of an ARN ({@code arn:aws:iam::412398765012:role/x}), or {@code null}. */
  static String accountOf(String arn) {
    if (arn == null) {
      return null;
    }
    String[] parts = arn.split(":", 6);
    return parts.length > 4 && parts[0].equals("arn") && ACCOUNT.matcher(parts[4]).matches() ? parts[4] : null;
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || n.asString("").isBlank() ? null : n.asString().strip();
  }

  private static String abbreviate(String s) {
    return s.length() <= 60 ? s : s.substring(0, 60) + "…";
  }
}
