package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * A connection's config as the connector uses it ({@code config.schema.json}); secrets are only named here (JSON
 * pointers), their values come from the call's {@code SecretResolver}.
 *
 * @param jump         the bastion, or {@code null}
 * @param runbooksOnly {@code ssh_exec} is refused on this connection whatever the policy says ("Runbooks only")
 */
record SshSettings(Target target, Credentials credentials, HostKeyConfig hostKeys, Jump jump, Advanced advanced,
    boolean runbooksOnly) {

  /** {@code gw@10.20.0.14:22}. */
  record Target(String host, int port, String username) {
    String label() {
      return username + "@" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    String address() {
      return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
  }

  enum Method {
    VAULT_SSH_CA("vaultSshCa", "Vault SSH CA certificate"), PRIVATE_KEY("privateKey", "private key"),
    PASSWORD("password", "password");

    final String id;
    final String label;

    Method(String id, String label) {
      this.id = id;
      this.label = label;
    }

    static Method of(String id) {
      for (Method m : values()) {
        if (m.id.equals(id)) {
          return m;
        }
      }
      return null;
    }
  }

  /**
   * How to sign in.
   *
   * @param pointer     the JSON pointer of the credentials object: {@code /credentials} or {@code /jump/credentials}
   * @param certificate an OpenSSH user certificate for the private key ({@code …-cert.pub}), not a secret
   */
  record Credentials(String pointer, Method method, Vault vault, String certificate) {
    String password() {
      return pointer + "/password";
    }

    String privateKey() {
      return pointer + "/privateKey";
    }

    String passphrase() {
      return pointer + "/passphrase";
    }

    /** The field named to the core's Vault SSH CA signer ({@code SecretResolver.sshCertificate}). */
    String vaultField() {
      return pointer + "/vault";
    }
  }

  /** {@code ssh-client-signer/sign/gw-runbooks}, a certificate of {@code ttl}. */
  record Vault(String store, String mount, String role, Duration ttl, String principal) {}

  /** Pinned keys and {@code known_hosts} lines; {@code pointer} names the object for the Trust patch. */
  record HostKeyConfig(String pointer, List<String> pinned, String knownHosts) {}

  record Jump(Target target, Credentials credentials, HostKeyConfig hostKeys) {}

  record Advanced(Duration connectTimeout, Duration keepalive, boolean reuseSessions, int maxChannels,
      boolean remoteDeadline, boolean legacyAlgorithms, boolean probeSudo) {}

  static SshSettings of(ConnectionConfig cfg) throws ConnectorException {
    JsonNode c = cfg.config();
    Target target = target(c.path("connection"), null, "connection");
    Credentials credentials = credentials(c.path("credentials"), "/credentials");
    HostKeyConfig hostKeys = hostKeys(c.path("hostKeys"), "/hostKeys");
    Jump jump = null;
    JsonNode j = c.path("jump");
    if (j.isObject() && !j.path("host").asString("").isBlank()) {
      Target jt = target(j, target.username(), "jump");
      // Without credentials of its own the bastion takes the host's, and the host's secret fields.
      Credentials jc = j.path("credentials").isObject()
          ? credentials(j.path("credentials"), "/jump/credentials") : credentials;
      jump = new Jump(jt, jc, hostKeys(j.path("hostKeys"), "/jump/hostKeys"));
    }
    JsonNode a = c.path("advanced");
    Advanced advanced = new Advanced(Duration.ofSeconds(clamp(a.path("connectTimeoutSeconds").asInt(10), 1, 60)),
        Duration.ofSeconds(clamp(a.path("keepaliveSeconds").asInt(15), 0, 300)),
        a.path("reuseSessions").asBoolean(true), clamp(a.path("maxChannelsPerSession").asInt(8), 1, 10),
        a.path("remoteDeadline").asBoolean(false), a.path("legacyAlgorithms").asBoolean(false),
        a.path("probeSudo").asBoolean(true));
    // The core doesn't fill x-env-default into a saved config: an unset switch is on for prod (report 06 §4).
    boolean runbooksOnly = c.path("runbooksOnly").asBoolean(cfg.env() == Env.PROD);
    return new SshSettings(target, credentials, hostKeys, jump, advanced, runbooksOnly);
  }

  /** {@code gw@10.20.0.14:22}, with the bastion when there is one: what approvers read as the target. */
  String targetLabel() {
    return jump == null ? target.label() : target.label() + " via " + jump.target().address();
  }

  private static Target target(JsonNode n, String defaultUser, String at) throws ConnectorException {
    String host = n.path("host").asString("").strip();
    if (host.isEmpty() || host.length() > 253 || host.chars().anyMatch(ch -> ch <= ' ' || ch == '/' || ch == '@')) {
      throw ConnectorException.badInput(at + ".host must be a host name or an address.");
    }
    int port = n.path("port").asInt(22);
    if (port < 1 || port > 65535) {
      throw ConnectorException.badInput(at + ".port must be 1–65535.");
    }
    String user = n.path("username").asString(defaultUser == null ? "" : defaultUser);
    if (user == null || !user.matches("^[a-z_][a-z0-9_.-]{0,31}$")) {
      throw ConnectorException.badInput(at + ".username must be a user name such as deploy.");
    }
    return new Target(host.toLowerCase(Locale.ROOT), port, user);
  }

  private static Credentials credentials(JsonNode n, String pointer) throws ConnectorException {
    Method method = Method.of(n.path("method").asString("vaultSshCa"));
    if (method == null) {
      throw ConnectorException.badInput("credentials.method must be vaultSshCa, privateKey or password.");
    }
    JsonNode v = n.path("vault");
    Vault vault = null;
    if (method == Method.VAULT_SSH_CA) {
      String ttl = v.path("ttl").asString("5m");
      Duration duration = switch (ttl) {
        case "1m" -> Duration.ofMinutes(1);
        case "2m" -> Duration.ofMinutes(2);
        case "10m" -> Duration.ofMinutes(10);
        default -> Duration.ofMinutes(5);
      };
      String principal = v.path("principal").asString("");
      vault = new Vault(v.path("store").asString(""), v.path("mount").asString("ssh-client-signer"),
          v.path("role").asString("gw-runbooks"), duration, principal.isBlank() ? null : principal);
    }
    String certificate = n.path("certificate").asString("").strip();
    return new Credentials(pointer, method, vault, certificate.isEmpty() ? null : certificate);
  }

  private static HostKeyConfig hostKeys(JsonNode n, String pointer) {
    List<String> pinned = new ArrayList<>();
    for (JsonNode p : n.path("pinned")) {
      if (p.isString() && !p.asString().isBlank()) {
        pinned.add(p.asString().strip());
      }
    }
    return new HostKeyConfig(pointer, List.copyOf(pinned), n.path("knownHosts").asString(""));
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }
}
