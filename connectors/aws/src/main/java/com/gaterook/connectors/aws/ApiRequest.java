package com.gaterook.connectors.aws;

import com.gaterook.connector.api.ConnectionConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One call of {@code aws_api_read} or {@code aws_api_change}, checked without I/O — the same checks in
 * {@code classify}, {@code describeAction} and {@code execute}: the region (the connection's, allowed by the policy),
 * the service (in the catalog, callable, its IAM prefix allowed by the Cloud policy), the operation (in the model, not
 * refused by {@link ApiRules}, with an endpoint in the region, a read for {@code aws_api_read} and a change for
 * {@code aws_api_change}), the parameters ({@link ApiParams}), ARNs of another account or outside the resource masks,
 * the cursor, and whether the session policy fits STS.
 *
 * @param tool       {@link #READ} or {@link #CHANGE}
 * @param parameters canonical, idempotency tokens filled: what the approval shows and what runs
 * @param arns       ARNs named in the parameters
 */
record ApiRequest(String tool, String region, ApiModel model, ApiModel.Operation op, ApiRules.Access access,
    ObjectNode parameters, List<String> arns, String cursor) {

  static final String READ = "aws_api_read";
  static final String CHANGE = "aws_api_change";

  static boolean isTool(String tool) {
    return READ.equals(tool) || CHANGE.equals(tool);
  }

  /**
   * The checked call.
   *
   * @param p the environment's Cloud policy as {@code classify} sees it, or {@code null} for the approval preview,
   *          which has none: it shows the request, and {@code classify} has already applied the policy to it
   */
  static ApiRequest of(String tool, JsonNode args, ConnectionConfig cfg, Settings s, AwsPolicy p, String callId)
      throws Refusal {
    String region = p != null ? Requests.region(args, cfg.name(), s, p) : connectionRegion(args, cfg, s);
    String service = args.path("service").asString("").strip();
    if (service.isEmpty()) {
      throw new Refusal("bad_input", "Name the service as the AWS CLI does: ecs, lambda, elbv2, logs.", null);
    }
    ApiCatalog catalog = ApiCatalog.shared();
    ApiCatalog.Entry entry = catalog.find(service);
    if (entry == null) {
      throw new Refusal("bad_input", "AWS has no service \"" + Requests.abbreviate(service) + "\" that GateRook "
          + "knows. Name it as the AWS CLI does: ecs, lambda, elbv2, logs, ce.", null);
    }
    if (entry.unsupported() != null) {
      throw new Refusal("unsupported", entry.unsupported(), null);
    }
    ApiModel model = catalog.model(entry);
    String spelled = args.path("operation").asString("").strip();
    ApiModel.Operation op = model.operation(spelled);
    if (op == null) {
      throw new Refusal("bad_input", model.sdkId + " has no operation \"" + Requests.abbreviate(spelled) + "\".",
          "describe_connection with object \"" + model.name + "\" lists its operations.");
    }
    ApiRules.Denial denial = ApiRules.denial(model.name, op);
    if (denial != null) {
      throw new Refusal(denial.rule(), denial.reason(), denial.hint());
    }
    ApiRules.Access access = ApiRules.access(model.name, op);
    if (tool.equals(READ) && access == ApiRules.Access.CHANGE) {
      throw new Refusal("change_tool", model.name + " " + op.name() + " changes something, so aws_api_read doesn't "
          + "call it.", "Call it with aws_api_change: a person approves the exact request.");
    }
    if (tool.equals(CHANGE) && access == ApiRules.Access.READ) {
      throw new Refusal("read_tool", model.name + " " + op.name() + " only reads.", "Call it with aws_api_read: it "
          + "runs at once.");
    }
    if (p != null) {
      Requests.service(model.iam, model.sdkId + " (" + model.name + ")", p);
    }
    model.endpoint(op, region);
    ApiParams.Checked checked = ApiParams.check(model, op, args.get("parameters"), callId);
    for (String arn : checked.arns()) {
      String account = Settings.accountOf(arn);
      if (account != null && s.account() != null && !account.equals(s.account())) {
        throw new Refusal("scope_not_allowed", Requests.abbreviate(arn) + " is in account " + account + "; "
            + cfg.name() + " reads account " + s.account() + ".", null);
      }
    }
    if (p != null && p.resourcesRestricted()) {
      if (!s.enforcedByAws()) {
        throw new Refusal("resources_need_session_policy", "The " + p.env().id() + " policy limits the resources "
            + "agents see. The AWS API tools keep to them only through the session policy AWS enforces, and "
            + cfg.name() + " has none (it needs a role with the generated session policy).", null);
      }
      if (ApiPolicies.resources(p.resources()).isEmpty()) {
        throw new Refusal("resource_not_allowed", "The " + p.env().id() + " policy's resource masks name no AWS "
            + "ARN, so no AWS resource is allowed.", null);
      }
      for (String arn : checked.arns()) {
        if (!p.resourceAllowed(arn)) {
          throw new Refusal("resource_not_allowed", Requests.abbreviate(arn) + " is outside the resources the "
              + p.env().id() + " policy allows.", null);
        }
      }
    }
    String cursor = null;
    if (args.hasNonNull("cursor") && tool.equals(READ)) {
      cursor = args.get("cursor").asString("");
      if (op.paging() == null || op.paging().inputToken() == null) {
        throw new Refusal("bad_input", model.name + " " + op.name() + " has no pages: leave cursor out.", null);
      }
      if (!cursor.startsWith(Cursors.PREFIX) || cursor.length() > 4096) {
        throw new Refusal("bad_input", "This cursor doesn't come from aws_api_read. Pass nextCursor of the previous "
            + "answer, or leave cursor out.", null);
      }
      if (checked.parameters().has(op.paging().inputToken())) {
        throw new Refusal("bad_input", "Pass either cursor or parameters." + op.paging().inputToken() + ", not "
            + "both.", null);
      }
    }
    ApiRequest r = new ApiRequest(tool, region, model, op, access, checked.parameters(), checked.arns(), cursor);
    if (p != null && s.enforcedByAws()) {
      try {
        r.sessionPolicy(p);
      } catch (IllegalStateException e) {
        throw new Refusal("session_policy_too_large", e.getMessage(), "Ask the person to list fewer resources on "
            + p.env().id() + ".");
      }
    }
    return r;
  }

  /** The call's region, one of the connection's: without a policy, nothing else narrows it. */
  private static String connectionRegion(JsonNode args, ConnectionConfig cfg, Settings s) throws Refusal {
    String region = args.hasNonNull("region") ? args.get("region").asString("").strip() : s.firstRegion();
    if (!s.hasRegion(region)) {
      throw new Refusal("scope_not_allowed", "\"" + Requests.abbreviate(region) + "\" isn't a region of "
          + cfg.name() + ".", null);
    }
    return region;
  }

  /** The session policy AssumeRole carries for this call. */
  String sessionPolicy(AwsPolicy p) {
    return ApiPolicies.of(model, op, access, p.resourcesRestricted() ? p.resources() : List.of());
  }

  /** {@code ecs:UpdateService}, or {@code apigateway:PATCH}. */
  String iamAction() {
    return ApiPolicies.action(model, op);
  }

  /** The canonical request of an approval: what runs after a person approves it. */
  ObjectNode payload() {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("tool", tool);
    n.put("service", model.name);
    n.put("operation", op.name());
    n.put("region", region);
    n.set("parameters", parameters.deepCopy());
    if (cursor != null) {
      n.put("cursor", cursor);
    }
    return n;
  }

  /** The request without its page: a cursor continues only the request it came from. */
  String hash() {
    ObjectNode n = payload();
    n.remove("cursor");
    if (op.paging() != null && op.paging().inputToken() != null) {
      ((ObjectNode) n.get("parameters")).remove(op.paging().inputToken());
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(n.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
