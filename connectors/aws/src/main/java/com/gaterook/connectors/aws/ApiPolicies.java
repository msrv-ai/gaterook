package com.gaterook.connectors.aws;

import com.gaterook.connectors.cloudauth.AwsSessionPolicy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The session policy of one AWS API call ({@code aws_api_read}, {@code aws_api_change}), which AWS enforces when the
 * connection assumes a role with the generated policy ({@link Settings#enforcedByAws}):
 *
 * <ul>
 *   <li>a read: the service's read verbs ({@code ecs:Describe*}, {@code List*}, {@code Get*}, {@code BatchGet*},
 *       {@code Search*}, {@code Lookup*}), plus the operation's own action when no verb covers it; one session per
 *       service, so reads of one service share it;</li>
 *   <li>an approved change: only the operation's action ({@code ecs:UpdateService}); API Gateway authorizes by HTTP
 *       method, so its action is {@code apigateway:PATCH} and the like;</li>
 *   <li>both: the explicit Deny of {@link SessionPolicyFactory#DENY} and of the operations {@link ApiRules} refuses on
 *       that service, whatever a broad role allows; a change's own action is the one exception (an approved SQS
 *       receive needs {@code sqs:ReceiveMessage}).</li>
 * </ul>
 * With resource masks the Allow names the masked ARNs instead of {@code *} ({@code ?} and {@code $} escaped as IAM
 * policy variables; GateRook's {@code *} is IAM's), so AWS refuses any resource outside them, and an action that
 * can't be limited to resources is refused while masks are set.
 */
final class ApiPolicies {

  private ApiPolicies() {}

  /** The session policy JSON. */
  static String of(ApiModel m, ApiModel.Operation op, ApiRules.Access access, List<String> masks) {
    List<String> resources = resources(masks);
    List<String> allow = new ArrayList<>();
    String action = action(m, op);
    if (access == ApiRules.Access.READ) {
      if (m.iam.equals("apigateway")) {
        allow.add("apigateway:GET");
      } else {
        allow.addAll(ApiRules.readWildcards(m.iam));
      }
      if (!allow.contains(action) && !ApiRules.coveredByReadWildcards(op) && !m.iam.equals("apigateway")) {
        allow.add(action);
      }
    } else {
      allow.add(action);
    }
    Set<String> deny = new LinkedHashSet<>(SessionPolicyFactory.DENY);
    for (String name : ApiRules.deniedOperations(m.name)) {
      ApiModel.Operation denied = m.operation(name);
      if (denied != null && !m.iam.equals("apigateway")) {
        deny.add(m.iam + ":" + denied.action());
      }
    }
    if (access == ApiRules.Access.CHANGE) {
      // The approved action itself; a wildcard Deny that covers it stays, as it covers more.
      deny.remove(action);
    }
    return AwsSessionPolicy.builder().allow(allow, resources).deny(new ArrayList<>(deny), List.of()).toJson();
  }

  /** The IAM action of {@code op}: {@code <prefix>:<action>}, or the HTTP method for API Gateway. */
  static String action(ApiModel m, ApiModel.Operation op) {
    if (m.iam.equals("apigateway")) {
      return "apigateway:" + op.method().toUpperCase(Locale.ROOT);
    }
    return m.iam + ":" + op.action();
  }

  /** The masks as IAM resources: ARNs only, IAM's wildcard characters escaped; empty for "every resource". */
  static List<String> resources(List<String> masks) {
    List<String> out = new ArrayList<>();
    for (String mask : masks) {
      if (mask.startsWith("arn:")) {
        out.add(mask.replace("$", "${$}").replace("?", "${?}"));
      }
    }
    return out;
  }
}
