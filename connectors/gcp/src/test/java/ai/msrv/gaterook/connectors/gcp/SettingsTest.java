package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which series and alerts belong to another project than the connection's own (C7 review, finding 3): only what can
 * be told apart is left out — never a project the connection names by number while its id is still unknown.
 */
class SettingsTest {

  private static Settings settings(String config, String metadata) throws Exception {
    return Settings.parse(new ConnectionConfig("c-gcp", "gcp-stage", "gcp", Env.STAGE, 1, TestCtx.JSON.readTree(config),
        Set.of(), metadata == null ? null : TestCtx.JSON.readTree(metadata)));
  }

  @Test
  void projectsByIdTellTheirOwnFromAnotherProject() throws Exception {
    Settings s = settings("{\"scopes\":[\"projects/acme-stage\"],\"credentials\":{\"source\":\"adc\"}}", null);

    assertThat(s.foreignProject("acme-stage")).isFalse();
    assertThat(s.foreignProject("acme-prod")).isTrue();
    assertThat(s.foreignProject(null)).as("no label: can't tell").isFalse();
  }

  @Test
  void aProjectByNumberCountsOnceItsIdIsKnown() throws Exception {
    String config = "{\"scopes\":[\"projects/123456789012\"],\"credentials\":{\"source\":\"adc\"}}";
    Settings unknown = settings(config, null);
    Settings known = settings(config, "{\"projects\":{\"acme-stage\":\"123456789012\"}}");

    assertThat(unknown.foreignProject("acme-stage")).as("its id isn't known yet: nothing is left out").isFalse();
    assertThat(unknown.foreignProject("acme-prod")).isFalse();
    assertThat(known.foreignProject("acme-stage")).isFalse();
    assertThat(known.foreignProject("acme-prod")).isTrue();
  }

  @Test
  void aFolderOrOrganizationListsNoProjects() throws Exception {
    Settings s = settings("{\"scopes\":[\"projects/acme-stage\",\"folders/123456\"],\"credentials\":{\"source\":"
        + "\"adc\"}}", null);

    assertThat(s.foreignProject("acme-prod")).isFalse();
  }
}
