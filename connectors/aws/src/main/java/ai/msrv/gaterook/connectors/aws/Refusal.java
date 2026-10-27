package ai.msrv.gaterook.connectors.aws;

/**
 * A call the policy or the connection doesn't allow, found without I/O: {@code classify} turns it into DENY
 * {@code <env>.cloud.<rule>}, {@code execute} into a refusal for the agent.
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

  /** The text the agent gets when {@code execute} refuses. */
  String forAgent() {
    return hint == null ? getMessage() : getMessage() + " " + hint;
  }
}
