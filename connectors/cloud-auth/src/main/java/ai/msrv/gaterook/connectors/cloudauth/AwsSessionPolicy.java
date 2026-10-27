package ai.msrv.gaterook.connectors.cloudauth;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * An inline session policy for {@code AssumeRole} (report 09 §6, report 12 §6): AWS intersects it with the role, so
 * even a broad role only does what the gateway asked for in this session — "Read only for this session: AWS enforces
 * the gateway's session policy". Statements are kept in order; the JSON is compact because STS counts characters
 * (2,048 for inline and managed policies together).
 */
public final class AwsSessionPolicy {

  /** STS refuses larger packed policies ({@code PackedPolicyTooLarge}). */
  public static final int MAX_CHARACTERS = 2048;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final List<ObjectNode> statements = new ArrayList<>();

  private AwsSessionPolicy() {}

  public static AwsSessionPolicy builder() {
    return new AwsSessionPolicy();
  }

  /** {@code Allow} of {@code actions} on {@code resources} ({@code *} when empty). */
  public AwsSessionPolicy allow(List<String> actions, List<String> resources) {
    statements.add(statement("Allow", actions, resources));
    return this;
  }

  /** Explicit {@code Deny}: wins over any allow of the role, e.g. {@code secretsmanager:GetSecretValue}. */
  public AwsSessionPolicy deny(List<String> actions, List<String> resources) {
    statements.add(statement("Deny", actions, resources));
    return this;
  }

  /**
   * The policy document.
   *
   * @throws IllegalStateException without statements or above {@value #MAX_CHARACTERS} characters
   */
  public String toJson() {
    if (statements.isEmpty()) {
      throw new IllegalStateException("A session policy needs at least one statement");
    }
    ObjectNode policy = JsonNodeFactory.instance.objectNode();
    policy.put("Version", "2012-10-17");
    ArrayNode list = policy.putArray("Statement");
    statements.forEach(list::add);
    String json = JSON.writeValueAsString(policy);
    if (json.length() > MAX_CHARACTERS) {
      throw new IllegalStateException("The session policy has " + json.length() + " characters; STS accepts "
          + MAX_CHARACTERS + ". Allow fewer services or resources.");
    }
    return json;
  }

  private static ObjectNode statement(String effect, List<String> actions, List<String> resources) {
    if (actions.isEmpty()) {
      throw new IllegalArgumentException("A statement needs actions");
    }
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("Effect", effect);
    ArrayNode a = s.putArray("Action");
    actions.forEach(a::add);
    ArrayNode r = s.putArray("Resource");
    if (resources.isEmpty()) {
      r.add("*");
    } else {
      resources.forEach(r::add);
    }
    return s;
  }
}
