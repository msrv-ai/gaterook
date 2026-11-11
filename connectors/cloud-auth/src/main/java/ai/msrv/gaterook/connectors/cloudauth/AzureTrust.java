package ai.msrv.gaterook.connectors.cloudauth;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import java.time.Duration;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What Test connection of an Azure connection asks the person before anything runs or leaves the computer, shared by
 * the Azure and Azure Monitor Logs connectors:
 *
 * <ul>
 *   <li>{@code azure-cli}: the exact Azure CLI command GateRook would run with the person's rights; Trust writes its
 *       fingerprint to {@code /trustedCli}, a setting only the person makes ({@code x-person-only});</li>
 *   <li>{@code azure-sign-in}: the code of a device sign-in to enter in a browser; the patch is empty — the decision
 *       is the person's sign-in itself, and testing again collects it.</li>
 * </ul>
 */
public final class AzureTrust {

  private AzureTrust() {}

  /**
   * The confirmation Test connection stops for, or {@code null} when nothing waits for the person.
   *
   * @throws ConnectorException {@code RESOURCE_UNAVAILABLE} when the Azure CLI isn't installed or Entra ID refused the
   *                            device sign-in
   */
  public static TestResult.Confirmation decision(AzureCredentials azure, AzureSpec spec, String connectionName,
      Duration budget) throws ConnectorException {
    if (spec.source() == AzureSpec.Source.CLI) {
      AzureCredentials.CliCommand command = azure.cliCommand(spec);
      if (command.fingerprint().equals(spec.trustedCli())) {
        return null;
      }
      String text = "Run this command on this computer, with your user rights, whenever " + connectionName
          + " needs a new Azure token: " + command.commandLine();
      ObjectNode data = JsonNodeFactory.instance.objectNode();
      data.put("command", command.program().toString());
      ArrayNode args = data.putArray("args");
      command.args().forEach(args::add);
      ArrayNode env = data.putArray("env");
      command.environment().forEach(env::add);
      data.put("fingerprint", command.fingerprint());
      ObjectNode patch = JsonNodeFactory.instance.objectNode().put("trustedCli", command.fingerprint());
      return new TestResult.Confirmation("azure-cli", text, data, patch);
    }
    if (spec.source() == AzureSpec.Source.DEVICE_CODE) {
      AzureCredentials.DeviceSignIn signIn = azure.deviceSignIn(spec, budget);
      if (signIn instanceof AzureCredentials.DeviceSignIn.Pending p) {
        String text = "Open " + p.verificationUri() + " in a browser and enter the code " + p.userCode() + " to sign "
            + "in to tenant " + spec.tenantId() + " with the account " + connectionName + " should read as. Then "
            + "choose \"I signed in\". The code expires at " + p.expiresAt() + ".";
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("verificationUri", p.verificationUri().toString());
        data.put("userCode", p.userCode());
        data.put("expiresAt", p.expiresAt().toString());
        data.put("tenant", spec.tenantId());
        return new TestResult.Confirmation("azure-sign-in", text, data, JsonNodeFactory.instance.objectNode());
      }
    }
    return null;
  }
}
