package ai.msrv.gaterook.connectors.snowflake;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The settings of one Snowflake connection ({@code config.schema.json}). The address is derived from the account
 * identifier and nothing else: {@code https://<account>.snowflakecomputing.com}, or
 * {@code <account>.privatelink.snowflakecomputing.com} with private connectivity. A person can't type a URL, and so
 * can't send a key or a token anywhere but to Snowflake.
 *
 * @param account                 the account identifier as entered: {@code myorg-myaccount}, or an account locator
 *                                with its region and cloud ({@code xy12345.eu-central-1})
 * @param host                    where the SQL API is: the identifier in lower case, {@code _} as {@code -}
 * @param jwtAccount              the account in a key pair's JWT: the identifier up to its first dot, upper case
 * @param warehouse               where statements run, as Snowflake stores the name; {@code null}: the user's default
 * @param role                    the role statements run with; {@code null}: the user's default role
 * @param database                the current database of every statement; {@code null}: the user's default namespace
 * @param schema                  the current schema of every statement; {@code null}: the user's default namespace
 * @param tables                  the tables agents may use
 * @param statementTimeoutSeconds the connection's own statement timeout, 0 when it has none: a call gets the stricter
 *                                of it and the policy's
 */
record SnowflakeSettings(String account, boolean privateLink, String host, String jwtAccount, String user, Auth auth,
    String warehouse, String role, String database, String schema, TableList tables, int statementTimeoutSeconds) {

  /** How the connection signs in. */
  enum Auth { KEY_PAIR, TOKEN }

  static final String PRIVATE_KEY = "/authentication/privateKey";
  static final String TOKEN = "/authentication/token";

  /** {@code orgname-accountname}: the organization's name, a hyphen, the account's name. */
  private static final Pattern ORG_ACCOUNT = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,254}-[A-Za-z0-9_]{1,255}");
  /** An account locator, optionally with its region and cloud: {@code xy12345.us-east-2.aws}. */
  private static final Pattern LOCATOR = Pattern.compile("[A-Za-z0-9]{1,64}(\\.[A-Za-z0-9-]{1,64}){0,2}");
  /** Parts of a host someone pasted instead of the identifier. */
  private static final Set<String> HOST_PARTS = Set.of("privatelink", "snowflakecomputing", "global", "com", "cn",
      "app", "www");
  private static final Pattern USER = Pattern.compile("[A-Za-z0-9_.@$+-]{1,255}");

  static SnowflakeSettings of(ConnectionConfig cfg) throws ConnectorException {
    String account = blankToNull(cfg.string("/account"));
    if (account == null) {
      throw ConnectorException.badInput("Enter the Snowflake account identifier, such as myorg-myaccount.");
    }
    account = account.strip();
    if (!ORG_ACCOUNT.matcher(account).matches() && !LOCATOR.matcher(account).matches()) {
      throw ConnectorException.badInput("The account identifier is orgname-accountname (myorg-myaccount) or an "
          + "account locator with its region (xy12345.eu-central-1), not a URL.");
    }
    for (String part : account.toLowerCase(Locale.ROOT).split("\\.")) {
      if (HOST_PARTS.contains(part)) {
        throw ConnectorException.badInput("Enter the account identifier only, such as myorg-myaccount: GateRook adds "
            + ".snowflakecomputing.com. For a private link, turn on Private connectivity.");
      }
    }
    boolean privateLink = cfg.bool("/privateLink", false);
    String host = account.toLowerCase(Locale.ROOT).replace('_', '-') + (privateLink ? ".privatelink" : "")
        + ".snowflakecomputing.com";
    int dot = account.indexOf('.');
    String jwtAccount = (dot < 0 ? account : account.substring(0, dot)).toUpperCase(Locale.ROOT);

    String user = blankToNull(cfg.string("/user"));
    if (user == null || !USER.matcher(user.strip()).matches()) {
      throw ConnectorException.badInput("Enter the Snowflake user (its login name) that GateRook signs in as.");
    }
    Auth auth = switch (String.valueOf(cfg.string("/authentication/method"))) {
      case "keyPair" -> Auth.KEY_PAIR;
      case "token" -> Auth.TOKEN;
      default -> throw ConnectorException.badInput("Choose how GateRook signs in: a key pair or a programmatic "
          + "access token.");
    };
    String warehouse = identifier(cfg, "/warehouse", "warehouse");
    String role = identifier(cfg, "/role", "role");
    String database = identifier(cfg, "/database", "database");
    String schema = identifier(cfg, "/schema", "schema");
    if (schema != null && database == null) {
      throw ConnectorException.badInput("A schema needs its database: set Database too.");
    }
    TableList tables;
    try {
      tables = TableList.of(cfg.strings("/tables"));
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("Tables lists DATABASE.SCHEMA.TABLE names, * for any characters: "
          + e.getMessage() + ".");
    }
    int timeout = Math.max(0, Math.min(3_600, cfg.integer("/advanced/statementTimeoutSeconds", 0)));
    return new SnowflakeSettings(account, privateLink, host, jwtAccount, user.strip(), auth, warehouse, role,
        database, schema, tables, timeout);
  }

  private static String identifier(ConnectionConfig cfg, String pointer, String what) throws ConnectorException {
    String text = blankToNull(cfg.string(pointer));
    if (text == null) {
      return null;
    }
    String name = ObjectName.identifier(text);
    if (name == null) {
      throw ConnectorException.badInput("The " + what + " isn't a Snowflake name: write it as it was created, such "
          + "as ANALYTICS, or in double quotes for a name with lower case or spaces.");
    }
    return name;
  }

  /** The SQL API's base address. */
  URI base() {
    return URI.create("https://" + host);
  }

  /** The statement timeout of a call: the stricter of the connection's and the policy's, in whole seconds. */
  int statementTimeout(Duration policy) {
    long fromPolicy = policy == null || policy.isZero() || policy.isNegative() ? 0
        : Math.max(1, (policy.toMillis() + 999) / 1_000);
    long own = statementTimeoutSeconds;
    long out = own == 0 ? fromPolicy : fromPolicy == 0 ? own : Math.min(own, fromPolicy);
    return (int) Math.max(1, Math.min(3_600, out == 0 ? 30 : out));
  }

  /** {@code ANALYTICS.PUBLIC}, {@code ANALYTICS} or {@code null}: where unqualified names resolve. */
  String namespace() {
    if (database == null) {
      return null;
    }
    return ObjectName.quoteIfNeeded(database) + (schema == null ? "" : "." + ObjectName.quoteIfNeeded(schema));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
