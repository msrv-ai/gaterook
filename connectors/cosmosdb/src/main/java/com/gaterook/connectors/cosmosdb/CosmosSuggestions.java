package com.gaterook.connectors.cosmosdb;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.CloudLocal;
import com.gaterook.connectors.support.LocalPorts;
import com.gaterook.connectors.support.OutputSanitizer;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import tools.jackson.databind.JsonNode;

/**
 * Help for the Cosmos DB form ({@link com.gaterook.connector.api.Connector#suggest}).
 *
 * <p>On this computer: the account endpoint of a connection string pasted into Account endpoint or Account key (never
 * its key), the endpoint of an account name typed alone, the emulator when it answers on its port 8081, and for
 * Microsoft Entra ID the tenants of the Azure CLI's sign-in. Signed in: the databases the credentials may list, and
 * the containers of the allowed ones, read with two feeds per database at most.
 */
final class CosmosSuggestions {

  static final int EMULATOR_PORT = 8081;
  /** Databases whose containers are listed at most. */
  static final int MAX_DATABASES = 20;
  /** Time kept back from the deadline: no feed is read after it. */
  static final Duration LAST_FEED = Duration.ofMillis(1_500);
  static final Duration PROBE = Duration.ofMillis(1_200);
  static final String ACCOUNT = "the account";

  /** What the emulator's port answered. */
  enum Tls { VERIFIED, UNTRUSTED, PLAIN, NONE }

  /** An account's name typed alone: what goes before {@code .documents.azure.com}. */
  private static final Pattern ACCOUNT_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{1,42}[a-z0-9]");

  private CosmosSuggestions() {}

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  static Suggestions local(ConnectionConfig cfg, SecretResolver secrets, boolean plainLoopback, CloudLocal local,
      IntFunction<Tls> emulator) {
    Suggestions.Builder out = Suggestions.builder();
    String endpoint = text(cfg.string("/endpoint"));
    String pasted = endpointOf(endpoint, plainLoopback);
    if (pasted != null) {
      out.value("/endpoint", "the connection string", pasted, List.of(Option.of(pasted)));
      out.note("That is the account's connection string: its endpoint goes into Account endpoint, the text after "
          + "AccountKey= into Account key.");
    }
    if (secrets != null && secrets.isSet(CosmosSettings.ACCOUNT_KEY)) {
      try (SecretResolver.Secret key = secrets.secret(CosmosSettings.ACCOUNT_KEY)) {
        String inKey = endpointOf(key.unsafeString(), plainLoopback);
        if (inKey != null) {
          out.value("/endpoint", "the connection string", endpoint == null ? inKey : null, List.of(Option.of(inKey)));
          out.note("Account key holds the whole connection string: paste only the text after AccountKey=.");
        }
      } catch (ConnectorException e) {
        // Not typed here: nothing to read.
      }
    }
    if (endpoint != null && pasted == null && ACCOUNT_NAME.matcher(endpoint.toLowerCase(Locale.ROOT)).matches()) {
      String url = "https://" + endpoint.toLowerCase(Locale.ROOT) + CosmosEndpoint.Cloud.PUBLIC.suffix;
      out.options("/endpoint", "the account name", List.of(Option.of(url, "Azure; .documents.azure.us in Azure "
          + "Government, .documents.azure.cn in Azure operated by 21Vianet")));
    }
    if (endpoint == null || CosmosEndpoint.loopback(host(endpoint))) {
      emulator(out, plainLoopback, emulator, endpoint != null);
    }
    if ("entra".equals(cfg.string("/auth/method"))) {
      List<Option> tenants = local.azureTenantOptions();
      out.value("/auth/tenantId", "your Azure CLI sign-in", tenants.size() == 1 ? tenants.getFirst().value() : null,
          tenants);
    }
    return out.build();
  }

  /**
   * The emulator on its usual port, with the scheme it answers: offered, never filled in. What stands in its way is
   * said only once Account endpoint names this computer: something else may use the port.
   */
  private static void emulator(Suggestions.Builder out, boolean plainLoopback, IntFunction<Tls> probe,
      boolean chosen) {
    Option https = Option.of("https://localhost:" + EMULATOR_PORT, "the emulator's port answers");
    switch (probe.apply(EMULATOR_PORT)) {
      case VERIFIED -> out.options("/endpoint", "this computer", List.of(https));
      case UNTRUSTED -> {
        out.options("/endpoint", "this computer", List.of(https));
        if (chosen) {
          out.note("Port " + EMULATOR_PORT + " speaks TLS with a certificate this computer doesn't trust: for the "
              + "emulator, add its certificate (https://localhost:" + EMULATOR_PORT + "/_explorer/emulator.pem) to "
              + "the system's trusted certificates.");
        }
      }
      case PLAIN -> {
        if (plainLoopback) {
          out.options("/endpoint", "this computer", List.of(Option.of("http://localhost:" + EMULATOR_PORT,
              "the emulator's port answers without TLS")));
        } else if (chosen) {
          out.note("Port " + EMULATOR_PORT + " answers without TLS: start the emulator with --protocol https, as "
              + "GateRook reaches it over HTTPS.");
        }
      }
      case NONE -> { }
    }
  }

  /**
   * The account endpoint of a connection string ({@code AccountEndpoint=https://acme.documents.azure.com:443/;
   * AccountKey=…;}) as the form writes it, or {@code null}. Only the endpoint is read; the key stays where it was.
   */
  static String endpointOf(String text, boolean plainLoopback) {
    if (text == null || text.indexOf(';') < 0) {
      return null;
    }
    for (String part : text.split(";")) {
      int eq = part.indexOf('=');
      if (eq > 0 && part.substring(0, eq).strip().equalsIgnoreCase("AccountEndpoint")) {
        try {
          return CosmosEndpoint.parse(part.substring(eq + 1).strip(), plainLoopback).base().toString();
        } catch (CosmosEndpoint.Invalid e) {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * One TLS handshake with the port on {@code 127.0.0.1} (the default trust store; a ClientHello is all that is sent):
   * whether it speaks TLS, with a trusted certificate or not, or answers in plain text.
   */
  static Tls probe(int port) {
    if (!LocalPorts.listening(port)) {
      return Tls.NONE;
    }
    int millis = (int) PROBE.toMillis();
    try (Socket transport = new Socket()) {
      transport.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), millis);
      transport.setSoTimeout(millis);
      try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
          .createSocket(transport, "localhost", port, true)) {
        socket.startHandshake();
        return Tls.VERIFIED;
      } catch (IOException e) {
        return untrusted(e) ? Tls.UNTRUSTED : plaintext(e) ? Tls.PLAIN : Tls.NONE;
      }
    } catch (IOException e) {
      return Tls.NONE;
    }
  }

  /** The handshake reached the certificate check and failed it: the server speaks TLS. */
  private static boolean untrusted(Throwable error) {
    for (Throwable c = error; c != null; c = c.getCause() == c ? null : c.getCause()) {
      String m = String.valueOf(c.getMessage()).toLowerCase(Locale.ROOT);
      if (c instanceof CertificateException || m.contains("pkix") || m.contains("certificate")) {
        return true;
      }
    }
    return false;
  }

  /** A closed transport or a non-TLS answer, never an alert or a timeout. */
  private static boolean plaintext(Throwable error) {
    boolean eligible = false;
    for (Throwable c = error; c != null; c = c.getCause() == c ? null : c.getCause()) {
      String m = String.valueOf(c.getMessage()).toLowerCase(Locale.ROOT);
      if (c instanceof SocketTimeoutException || m.contains("fatal alert") || m.contains("handshake_failure")) {
        return false;
      }
      eligible |= c instanceof EOFException || c instanceof SocketException && m.contains("connection reset")
          || c instanceof SSLException && (m.contains("unrecognized ssl message") || m.contains("plaintext")
          || m.contains("ssl peer shut down incorrectly"));
    }
    return eligible;
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  static Suggestions service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, CosmosHttp http,
      boolean plainLoopback) {
    Suggestions.Builder out = Suggestions.builder();
    CosmosSettings s;
    try {
      s = CosmosSettings.of(cfg, plainLoopback);
    } catch (ConnectorException e) {
      return out.note(e.getMessage()).build();
    }
    CosmosHttp.Call c = new CosmosHttp.Call(cfg, s, secrets, ctx);
    CosmosHttp.Request feed = CosmosHttp.Request.get("/dbs", "dbs", "", "the databases")
        .with("x-ms-max-item-count", String.valueOf(Suggestions.MAX_OPTIONS));
    CosmosHttp.Answer dbs;
    try {
      dbs = http.send(c, feed, 403);
    } catch (ConnectorException e) {
      return out.note(e.getMessage()).build();
    }
    if (dbs.status() == 403) {
      if (!CosmosErrors.roleRefusal(dbs)) {
        return out.note(CosmosErrors.of(dbs, feed, c).getMessage()).build();
      }
      // Roles scoped to databases: the databases the form names are looked at one by one, as the list tool does.
      out.note("The credentials may not list the account's databases: name the ones agents use under Databases, "
          + "without *.");
      containers(c, http, s.databases().stream().filter(d -> d.indexOf('*') < 0).toList(), out);
      return out.build();
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode db : dbs.body().path("Databases")) {
      String id = db.path("id").asString("");
      if (CosmosIds.nameProblem(id) == null && !ids.contains(id)) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return out.note("Signed in to " + s.label() + ": the credentials see no database in the account.").build();
    }
    out.field("/databases", new Suggestions.Field(ids.stream().map(Option::of).toList(), null, ACCOUNT,
        dbs.continuation() == null));
    out.note("Signed in to " + s.label() + ": " + ids.size() + (dbs.continuation() == null ? "" : "+")
        + (ids.size() == 1 ? " database" : " databases") + ". Choose the ones agents may use; empty means all of "
        + "them.");
    containers(c, http, ids, out);
    return out.build();
  }

  /** The containers of the databases the connection allows, as {@code database/container}. */
  private static void containers(CosmosHttp.Call c, CosmosHttp http, List<String> databases,
      Suggestions.Builder out) {
    List<Option> options = new ArrayList<>();
    boolean complete = true;
    int read = 0;
    for (String db : databases) {
      if (!c.settings().allowsDatabase(db)) {
        continue;
      }
      if (read == MAX_DATABASES || options.size() >= Suggestions.MAX_OPTIONS
          || c.ctx().remaining().compareTo(LAST_FEED) <= 0) {
        complete = false;
        break;
      }
      read++;
      CosmosHttp.Answer a;
      try {
        a = http.send(c, CosmosHttp.Request.get("/dbs/" + CosmosIds.encode(db) + "/colls", "colls", "dbs/" + db,
            "the containers of " + CosmosIds.shown(db)).with("x-ms-max-item-count",
            String.valueOf(Suggestions.MAX_OPTIONS)), 403);
      } catch (ConnectorException e) {
        complete = false;
        break;
      }
      if (a.status() == 403) {
        complete = false;
        continue;
      }
      if (a.continuation() != null) {
        complete = false;
      }
      for (JsonNode coll : a.body().path("DocumentCollections")) {
        String id = coll.path("id").asString("");
        if (CosmosIds.nameProblem(id) != null) {
          continue;
        }
        List<String> paths = new ArrayList<>();
        coll.path("partitionKey").path("paths").forEach(p -> paths.add(OutputSanitizer.sanitize(p.asString(""))));
        options.add(Option.of(db + "/" + id, paths.isEmpty() ? null : "partition key " + String.join(", ", paths)));
      }
    }
    if (!options.isEmpty()) {
      out.field("/containers", new Suggestions.Field(options, null, ACCOUNT, complete));
    }
  }

  private static String host(String endpoint) {
    try {
      String h = java.net.URI.create(endpoint.contains("://") ? endpoint : "x://" + endpoint).getHost();
      return h == null ? "" : h.toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static String text(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
