package ai.msrv.gaterook.bridge;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;

/**
 * {@code gw-bridge headers --endpoint dev --grant gr_…}: Codex's {@code http_headers_helper} (report 03 §1, §3.2).
 * Prints {@code {"Authorization":"Bearer gwb_…"}} with a fresh 15-minute token and nothing else on stdout. Codex runs
 * it again after a 401 or 403, which is how the token rotates. Exit codes: 0 a token, 1 GateRook (or its broker)
 * isn't running, 2 the access is revoked or isn't for this endpoint and client, 3 wrong arguments. The reason goes to
 * {@code bridge.log} and stderr; Codex discards stderr.
 */
final class Headers {

  /** Below Codex's 10 s for a helper: our answer, even a refusal, reaches it. */
  static final Duration WAIT = Duration.ofSeconds(9);

  private Headers() {}

  static int run(Map<String, String> args, Map<String, String> env, PrintStream out, PrintStream err) {
    String endpoint = args.get("endpoint");
    String grant = args.get("grant");
    String client = args.getOrDefault("client", "codex");
    BrokerClient broker = BrokerClient.fromEnvironment(env);
    BridgeLog log = new BridgeLog(broker.home());
    if (!Main.validEndpoint(endpoint) || !Main.validGrant(grant) || !Main.validClient(client)) {
      String why = "usage: gw-bridge headers --endpoint <slug> --grant <gr_…> [--client codex]";
      log.write("headers", why);
      err.println(why);
      return 3;
    }
    Object answer;
    try {
      answer = broker.token(client, endpoint, grant, WAIT);
    } catch (BrokerClient.Unavailable e) {
      log.write("headers", client + " on " + endpoint + " (" + grant + "): " + e.getMessage());
      err.println(e.getMessage());
      return 1;
    }
    if (!Boolean.TRUE.equals(Json.get(answer, "ok"))) {
      String error = String.valueOf(Json.string(answer, "error"));
      String message = String.valueOf(Json.string(answer, "message"));
      log.write("headers", client + " on " + endpoint + " (" + grant + "): " + error + ": " + message);
      err.println(message);
      return exitFor(error);
    }
    String token = Json.string(answer, "token");
    if (token == null || !token.startsWith("gwb_")) {
      log.write("headers", client + " on " + endpoint + ": the broker's answer has no token");
      return 1;
    }
    out.print("{\"Authorization\":" + Json.quote("Bearer " + token) + "}");
    out.flush();
    return 0;
  }

  /** 2 for anything about the access; the rest means "not running now, try later". */
  static int exitFor(String error) {
    return switch (error) {
      case "grant_unknown", "grant_revoked", "grant_changed", "grant_mismatch", "confirmation_denied" -> 2;
      case "bad_request" -> 3;
      default -> 1;
    };
  }
}
