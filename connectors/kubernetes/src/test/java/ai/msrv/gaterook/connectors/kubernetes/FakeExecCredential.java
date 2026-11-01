package ai.msrv.gaterook.connectors.kubernetes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A real credential-plugin process that issues a new test token on each invocation. */
public final class FakeExecCredential {
  private FakeExecCredential() {}

  public static void main(String[] args) throws Exception {
    Path counter = Path.of(args[0]);
    int count = Files.exists(counter) ? Files.readAllLines(counter).size() : 0;
    Files.writeString(counter, "run\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    System.out.println("{\"apiVersion\":\"client.authentication.k8s.io/v1\",\"kind\":\"ExecCredential\","
        + "\"status\":{\"token\":\"cnry-exec-" + (count + 1) + "\"}}");
  }
}
