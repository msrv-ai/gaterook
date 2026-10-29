package ai.msrv.gaterook.connectors.sql;

import java.util.Map;
import java.util.function.Function;

/** Selects one disposable Oracle target without mixing external credentials with Compose settings. */
record OracleTestSettings(String host, int port, String service, String user, String password) {

  static boolean configured() {
    return hasHost(System.getenv()) || TestServices.enabled();
  }

  static OracleTestSettings current() {
    Map<String, String> environment = System.getenv();
    // An explicit external target does not depend on the Compose directory being present or valid.
    return resolve(environment, hasHost(environment) ? false : TestServices.enabled(), TestServices::value);
  }

  static OracleTestSettings resolve(Map<String, String> environment, boolean services,
      Function<String, String> composeValue) {
    if (hasHost(environment)) {
      return new OracleTestSettings(required(environment::get, "GW_ORACLE_HOST"),
          port(environment.getOrDefault("GW_ORACLE_PORT", "1521")),
          required(environment::get, "GW_ORACLE_SERVICE"), required(environment::get, "GW_ORACLE_USER"),
          required(environment::get, "GW_ORACLE_PASSWORD"));
    }
    if (!services) {
      throw new IllegalStateException("Oracle tests need -Pservices, GW_TEST_SERVICES, or GW_ORACLE_HOST.");
    }
    return new OracleTestSettings("127.0.0.1", port(required(composeValue, "GW_ORACLE_PORT")),
        required(composeValue, "GW_ORACLE_SERVICE"), required(composeValue, "GW_ORACLE_USER"),
        required(composeValue, "GW_ORACLE_PASSWORD"));
  }

  private static boolean hasHost(Map<String, String> environment) {
    String host = environment.get("GW_ORACLE_HOST");
    return host != null && !host.isBlank();
  }

  private static String required(Function<String, String> source, String key) {
    String value = source.apply(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Set " + key + " for the Oracle fixture.");
    }
    return value;
  }

  private static int port(String value) {
    try {
      int port = Integer.parseInt(value);
      if (port > 0 && port <= 65535) return port;
    } catch (NumberFormatException ignored) {
      // Report the setting name without exposing external connection details.
    }
    throw new IllegalStateException("GW_ORACLE_PORT must be an integer from 1 to 65535.");
  }

  @Override
  public String toString() {
    return "OracleTestSettings[host=" + host + ", port=" + port + ", service=" + service + ", user=" + user
        + ", password=<redacted>]";
  }
}
