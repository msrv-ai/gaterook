package ai.msrv.gaterook.connectors.cloudflare;

/**
 * A call the connection or the arguments don't allow, found without I/O: {@code classify} turns it into DENY
 * {@code <env>.cloudflare.<rule>}, {@code prepare} into a rejection and {@code execute} into a refusal for the agent.
 */
final class Refusal extends Exception {

  private static final long serialVersionUID = 1L;

  final String rule;
  final String hint;

  Refusal(String rule, String message, String hint) {
    super(message, null, false, false);
    this.rule = rule;
    this.hint = hint;
  }

  static Refusal badInput(String message, String hint) {
    return new Refusal("bad_input", message, hint);
  }

  /** The text the agent gets when {@code prepare} or {@code execute} refuses. */
  String forAgent() {
    return hint == null ? getMessage() : getMessage() + " " + hint;
  }
}
