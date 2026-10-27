package ai.msrv.gaterook.connectors.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes values that Cloud Control returns as plain configuration but that are secrets more often than not (report
 * 09 §1 #4, §6 "Redaction"), before the answer leaves the connector; the core's masker runs after it with the call's
 * taint. Counted, so the answer can say "3 fields masked".
 *
 * <ul>
 *   <li>{@code UserData} of instances and launch templates (bootstrap scripts with keys in them);</li>
 *   <li>{@code AssociationParameters[*].Value} of an instance's SSM associations;</li>
 *   <li>Lambda {@code Environment.Variables}, every value;</li>
 *   <li>environment variables in their other shapes: {@code Environment[*].Value} of ECS and Batch containers,
 *       {@code EnvironmentVariables[*].Value} of CodeBuild and Amplify, {@code RuntimeEnvironmentVariables} of App
 *       Runner, a SageMaker {@code Environment} map (its {@code UPPER_SNAKE} keys), Elastic Beanstalk
 *       {@code OptionSettings} of the {@code aws:elasticbeanstalk:application:environment} namespace;</li>
 *   <li>{@code HeaderValue} of custom headers ({@code OriginCustomHeaders} of CloudFront: the origin's shared
 *       secret), and {@code RepositoryCredentials};</li>
 *   <li>everywhere, a text value whose key names a secret ({@code password, passwd, secret, token, private_key,
 *       credential, connection_string, user_data, auth_string}: ElastiCache {@code AuthToken}, RDS
 *       {@code MasterUserPassword}).</li>
 * </ul>
 * References to secrets ({@code Secrets[*].ValueFrom}, {@code ...SecretArn}) carry ARNs only and stay readable,
 * except where the key itself names a secret value.
 */
final class Redactor {

  static final String MASK = "‹masked›";

  private static final Pattern SENSITIVE_KEY = Pattern.compile(
      "(?i).*(password|passwd|secret|token|private_?key|credential|connection_?string|user_?data|auth_?string).*");
  /** Keys that contain a sensitive word but hold references, counts or settings, not values. */
  private static final Pattern NOT_A_VALUE = Pattern.compile("(?i).*(count|ttl|seconds|enabled|type|policy|"
      + "version|expir\\w*|time|duration|rotation\\w*|name|id|ids|uri|url|ref|source|lifetime|arn|arns|length|"
      + "units|status|mode|from)$");
  /** Keys that hold environment variables: {@code Environment}, {@code EnvironmentVariables}, {@code EnvVars}… */
  private static final Pattern ENV_KEY = Pattern.compile("(?i)^((runtime|build)?environment(variables)?|envvars?)$");
  /** A key of an environment map, as programs name their variables ({@code SAGEMAKER_PROGRAM}, {@code DB_PASS}). */
  private static final Pattern ENV_NAME = Pattern.compile("^[A-Z_][A-Z0-9_]*$");

  private Redactor() {}

  /** Masks in place; returns the number of values masked. */
  static int redact(JsonNode node) {
    return walk(node);
  }

  private static int walk(JsonNode node) {
    int masked = 0;
    if (node instanceof ObjectNode o) {
      List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(o.properties());
      for (Map.Entry<String, JsonNode> f : fields) {
        String k = f.getKey();
        JsonNode v = f.getValue();
        boolean env = ENV_KEY.matcher(k).matches();
        if (k.equals("UserData") && v.isValueNode()) {
          masked += maskField(o, k);
        } else if (env && v.path("Variables").isObject()) {
          masked += maskAll((ObjectNode) v.path("Variables"));
        } else if ((env || k.equals("AssociationParameters")) && v.isArray()) {
          masked += maskItemValues((ArrayNode) v);
          masked += walk(v);
        } else if (env && v instanceof ObjectNode map) {
          // A SageMaker container's map of variables, or a CodeBuild environment whose EnvironmentVariables list the
          // walk masks: the variables are the UPPER_SNAKE keys, CodeBuild's own settings are PascalCase.
          for (String name : new ArrayList<>(map.propertyNames())) {
            if (ENV_NAME.matcher(name).matches()) {
              masked += maskField(map, name);
            }
          }
          masked += walk(v);
        } else if (k.endsWith("Headers") && v.isArray()) {
          for (JsonNode header : v) {
            if (header instanceof ObjectNode h) {
              masked += maskField(h, "HeaderValue");
              masked += maskField(h, "Value");
            }
          }
        } else if (k.equals("OptionSettings") && v.isArray()) {
          for (JsonNode option : v) {
            if (option instanceof ObjectNode setting
                && setting.path("Namespace").asString("").endsWith("application:environment")) {
              masked += maskField(setting, "Value");
            }
          }
          masked += walk(v);
        } else if (k.equals("RepositoryCredentials") && v.isObject()) {
          masked += maskAll((ObjectNode) v);
        } else if (v.isValueNode() && sensitiveKey(k)) {
          masked += maskField(o, k);
        } else {
          masked += walk(v);
        }
      }
    } else if (node instanceof ArrayNode a) {
      for (JsonNode item : a) {
        masked += walk(item);
      }
    }
    return masked;
  }

  static boolean sensitiveKey(String key) {
    return SENSITIVE_KEY.matcher(key).matches() && !NOT_A_VALUE.matcher(key).matches();
  }

  /** {@code [{Name|Key, Value}]}: every {@code Value} (any spelling) that holds something goes. */
  private static int maskItemValues(ArrayNode items) {
    int masked = 0;
    for (JsonNode item : items) {
      if (item instanceof ObjectNode o) {
        for (String name : new ArrayList<>(o.propertyNames())) {
          if (name.equalsIgnoreCase("value") || name.equalsIgnoreCase("values")) {
            masked += maskField(o, name);
          }
        }
      }
    }
    return masked;
  }

  private static int maskAll(ObjectNode o) {
    int masked = 0;
    for (String name : new ArrayList<>(o.propertyNames())) {
      masked += maskField(o, name);
    }
    return masked;
  }

  private static int maskField(ObjectNode o, String name) {
    JsonNode v = o.get(name);
    if (v == null || v.isNull() || (v.isString() && (v.asString().isEmpty() || v.asString().equals(MASK)))) {
      return 0;
    }
    o.put(name, MASK);
    return 1;
  }
}
