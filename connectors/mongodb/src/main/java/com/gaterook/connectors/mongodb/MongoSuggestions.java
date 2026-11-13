package com.gaterook.connectors.mongodb;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.SecretUnavailableException;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.spi.dns.DnsClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonValue;

/**
 * Help for the MongoDB form ({@link MongoConnector#suggest}). On this computer: {@code localhost} when a server answers
 * on 27017, offered while Host (or the connection string) is empty and never filled in. Signed in as Test connection
 * does: who the server says signed in, the transport Auto chose, and the databases the user may read, offered for
 * Collections the agent may read as {@code database.*}. The databases themselves are chosen in the form's database
 * step, so none is filled in here.
 */
final class MongoSuggestions {

  static final int PORT = 27017;
  static final String THIS_COMPUTER = "this computer";
  static final String SERVER = "the server";

  private MongoSuggestions() {}

  /** @param listening whether something accepts a TCP connection on a loopback port ({@code LocalPorts::listening}) */
  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      DnsClient dns, IntPredicate listening) {
    Suggestions.Builder out = Suggestions.builder();
    local(cfg, listening, out);
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, dns, out);
    }
    return out.build();
  }

  /** {@code localhost} for an empty Host, or a connection string for an empty one, when a server answers on 27017. */
  private static void local(ConnectionConfig cfg, IntPredicate listening, Suggestions.Builder out) {
    String uri = text(cfg, "/connection/uri");
    String mode = text(cfg, "/connection/mode");
    boolean uriMode = mode == null ? uri != null : mode.equals("uri");
    if ((uriMode ? uri : text(cfg, "/connection/host")) != null || !listening.test(PORT)) {
      return;
    }
    String detail = "A MongoDB server answers on port " + PORT;
    if (uriMode) {
      out.options("/connection/uri", THIS_COMPUTER, List.of(new Option(MongoUri.PREFIX + "localhost:" + PORT + "/",
          null, detail)));
    } else {
      out.options("/connection/host", THIS_COMPUTER, List.of(new Option("localhost", null, detail)));
    }
  }

  /** Signs in as Test connection does and lists the databases the user may read. */
  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, DnsClient dns,
      Suggestions.Builder out) {
    MongoSettings s = MongoSettings.of(cfg);
    if (!s.usable()) {
      out.note(s.problemText());
      return;
    }
    long budget = Math.max(1_000, Math.min(MongoProbe.BUDGET_MS, ctx.remaining().toMillis()));
    try (MongoLogin login = MongoLogin.resolve(s, secrets)) {
      if (login.user() != null && !login.hasPassword()) {
        out.note("Enter the password of " + login.user() + " to list its databases.");
        return;
      }
      MongoSettings used;
      MongoClient client;
      try {
        MongoClientSettings settings = ClientSettings.build(s, login, dns,
            Duration.ofMillis(Math.min(s.serverSelectionTimeoutMs(), budget)), Duration.ofMillis(budget));
        used = s.withTls(settings.getSslSettings().isEnabled());
        client = MongoClients.create(settings);
      } catch (MongoException | IllegalArgumentException e) {
        out.note(MongoErrors.test(e, s, null).error().message());
        return;
      }
      try (client) {
        MongoDatabase admin = client.getDatabase("admin");
        String user = user(admin.runCommand(new BsonDocument("connectionStatus", new BsonInt32(1)),
            BsonDocument.class));
        Namespaces visible = new Namespaces(List.of());
        List<Option> options = new ArrayList<>();
        int databases = 0;
        for (String db : MongoProbe.databases(admin)) {
          if (visible.databaseVisible(db) && Namespaces.validEntry(db + ".*")) {
            databases++;
            options.add(new Option(db + ".*", null, "every collection of " + db));
          }
        }
        out.options("/advanced/namespaceAllowlist", SERVER, options);
        if (s.autoTls()) {
          out.note(used.tls() ? "The server speaks TLS: Auto connects with TLS."
              : "The server doesn't speak TLS: Auto connects without it, so the traffic isn't encrypted.");
        }
        out.note((user == null ? "Connected without signing in" : "Signed in as " + user) + ". " + (databases == 0
            ? "The user can't list a database it may read." : databases + (databases == 1 ? " database" : " databases")
                + " to read: choose the ones agents may use."));
      } catch (MongoException e) {
        out.note(MongoErrors.test(e, used, client).error().message());
      }
    } catch (SecretUnavailableException e) {
      out.note(e.getMessage());
    }
  }

  /** The first user {@code connectionStatus} names, as {@code user@db}; {@code null} without one. */
  private static String user(BsonDocument status) {
    BsonArray users = status.getDocument("authInfo", new BsonDocument()).getArray("authenticatedUsers",
        new BsonArray());
    for (BsonValue u : users) {
      if (u.isDocument() && u.asDocument().isString("user")) {
        BsonDocument d = u.asDocument();
        return d.getString("user").getValue() + (d.isString("db") ? "@" + d.getString("db").getValue() : "");
      }
    }
    return null;
  }

  private static String text(ConnectionConfig cfg, String pointer) {
    String v = cfg.string(pointer);
    return v == null || v.isBlank() ? null : v.strip();
  }
}
