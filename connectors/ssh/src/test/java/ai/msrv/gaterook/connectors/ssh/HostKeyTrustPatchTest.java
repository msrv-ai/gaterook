package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.PersonOnly;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Trusting a host key is the person's decision: what the Trust patch writes into a connection is a setting only the
 * person makes ({@code x-person-only}), so an AI agent's import proposal cannot hold it.
 */
class HostKeyTrustPatchTest {

  private static final SshSettings.Target TARGET = new SshSettings.Target("host.example.test", 22, "reader");

  @Test
  void theTrustPatchWritesOnlySettingsOnlyThePersonMakes() throws Exception {
    // MINA must learn our security setup before anything else uses it, or the connectors of other tests would refuse.
    MinaSecurity.install();
    KeyPair pair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    SshConnector ssh = new SshConnector();
    SshLogsConnector logs = new SshLogsConnector();
    try {
      // The host's key and the bastion's key (SSH only), with a key already trusted: the patch keeps it and adds one.
      assertWritesPersonOnly(ssh.manifest().configSchema(), "/hostKeys", pair);
      assertWritesPersonOnly(ssh.manifest().configSchema(), "/jump/hostKeys", pair);
      assertWritesPersonOnly(logs.manifest().configSchema(), "/hostKeys", pair);
    } finally {
      ssh.close();
      logs.close();
    }
  }

  private static void assertWritesPersonOnly(JsonNode schema, String pointer, KeyPair pair) throws Exception {
    HostKeys.Check check = HostKeys.check(TARGET, new SshSettings.HostKeyConfig(pointer,
        List.of(HostKeys.line(pair.getPublic())), ""));
    JsonNode patch = SshProbe.patch(check, pair.getPublic());
    List<String> written = new ArrayList<>();
    leaves(patch, "", written);
    assertThat(written).containsExactly(pointer + "/pinned");
    assertThat(PersonOnly.covers(schema, pointer + "/pinned"))
        .as("Trust writes %s: mark it x-person-only in the schema", pointer + "/pinned").isTrue();
    // The pasted known_hosts lines trust a host just as a pin does.
    assertThat(PersonOnly.covers(schema, pointer + "/knownHosts")).isTrue();
  }

  private static void leaves(JsonNode node, String pointer, List<String> out) {
    if (node.isObject() && !node.isEmpty()) {
      node.properties().forEach(e -> leaves(e.getValue(), pointer + "/" + e.getKey(), out));
    } else {
      out.add(pointer);
    }
  }
}
