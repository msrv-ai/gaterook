package ai.msrv.gaterook.e2e.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.mcpserver.McpServerConnector;
import ai.msrv.gaterook.connectors.ssh.SshConnector;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OptionalConnectionFieldsTest {
  private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void sshAllowsAnOmittedBastionOrInheritedBastionCredentials() {
    var schema = SCHEMAS.getSchema(SchemaResources.of(SshConnector.class, "ssh").load("config"));
    for (String jump : new String[] {"{}", "{\"port\":22,\"credentials\":{\"method\":\"vaultSshCa\"}}"}) {
      assertThat(schema.validate(JSON.readTree("""
          {"connection":{"host":"ssh.example.test","username":"reader"},
           "credentials":{"method":"password","password":"stored-password"},"jump":%s}
          """.formatted(jump)))).as("No bastion host: %s", jump).isEmpty();
    }
    var withJump = JSON.readTree("""
        {"connection":{"host":"ssh.example.test","username":"reader"},
         "credentials":{"method":"password","password":"stored-password"},
         "jump":{"host":"bastion.example.test"}}
        """);
    assertThat(schema.validate(withJump)).as("The bastion may inherit the target user and credentials").isEmpty();
    assertThat(schema.validate(JSON.readTree("""
        {"connection":{"host":"ssh.example.test","username":"reader"},
         "credentials":{"method":"password","password":"stored-password"},
         "jump":{"host":"bastion.example.test","credentials":{"method":"inherit"}}}
        """))).as("The form can select inherited bastion credentials explicitly").isEmpty();
    assertThat(schema.validate(JSON.readTree("""
        {"connection":{"host":"ssh.example.test","username":"reader"},"credentials":{"method":"inherit"}}
        """))).as("The main connection has no parent credentials to inherit").isNotEmpty();
    assertThat(schema.validate(JSON.readTree("""
        {"connection":{"host":"ssh.example.test","username":"reader"},
         "credentials":{"method":"password","password":"stored-password"},
         "jump":{"host":"bastion.example.test","username":"jumpuser",
                 "credentials":{"method":"password","password":"stored-jump-password"}}}
        """))).isEmpty();
  }

  @Test
  void externalMcpRequiresOnlyTheSelectedTransportsAddress() {
    var schema = SCHEMAS.getSchema(McpServerConnector.typeManifest().configSchema());
    assertThat(schema.validate(JSON.readTree("""
        {"transport":"stdio","command":["node","/example/server.js"]}
        """))).isEmpty();
    assertThat(schema.validate(JSON.readTree("""
        {"transport":"http","url":"https://mcp.example.test/mcp","command":[]}
        """))).as("Inactive saved command settings do not block HTTP").isEmpty();
    for (String transport : new String[] {"stdio", "http"}) {
      assertThat(schema.validate(JSON.readTree("{\"transport\":\"" + transport + "\"}")))
          .as("The selected transport still needs its address").isNotEmpty();
    }
  }
}
