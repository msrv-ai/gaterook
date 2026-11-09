package ai.msrv.gaterook.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * The private ranges a policy allows to a connection by name follow its rename ({@link PolicyService#renameIn}): the
 * name is replaced where it is listed, a range for every connection stays as it is, and a list that names the new name
 * already doesn't name it twice.
 */
class PolicyConnectionRenameTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void theRenamedConnectionKeepsItsRangesAndNoOtherConnectionGainsOne() {
    JsonNode ranges = JSON.readTree("""
        [{"cidr":"10.30.4.12/32","connections":["billing-api"]},
         {"cidr":"10.30.0.0/16","connections":["crm-api","billing-api"]},
         {"cidr":"192.168.0.0/16","connections":[]},
         {"cidr":"172.16.0.0/12"},
         "10.0.0.0/8"]""");

    assertThat(PolicyService.renameIn(ranges, "billing-api", "billing-v2")).isTrue();

    assertThat(ranges).isEqualTo(JSON.readTree("""
        [{"cidr":"10.30.4.12/32","connections":["billing-v2"]},
         {"cidr":"10.30.0.0/16","connections":["crm-api","billing-v2"]},
         {"cidr":"192.168.0.0/16","connections":[]},
         {"cidr":"172.16.0.0/12"},
         "10.0.0.0/8"]"""));
  }

  @Test
  void aListThatNamesTheNewNameAlreadyLosesTheOldOne() {
    JsonNode ranges = JSON.readTree("""
        [{"cidr":"10.30.4.12/32","connections":["billing-v2","billing-api","billing-api"]}]""");

    assertThat(PolicyService.renameIn(ranges, "billing-api", "billing-v2")).isTrue();

    assertThat(ranges).isEqualTo(JSON.readTree("[{\"cidr\":\"10.30.4.12/32\",\"connections\":[\"billing-v2\"]}]"));
  }

  @Test
  void rangesThatDoNotNameTheConnectionAreLeftAlone() {
    JsonNode ranges = JSON.readTree("[{\"cidr\":\"10.30.4.12/32\",\"connections\":[\"crm-api\"]}]");

    assertThat(PolicyService.renameIn(ranges, "billing-api", "billing-v2")).isFalse();
    assertThat(PolicyService.renameIn(MissingNode.getInstance(), "billing-api", "billing-v2")).isFalse();
    assertThat(ranges).isEqualTo(JSON.readTree("[{\"cidr\":\"10.30.4.12/32\",\"connections\":[\"crm-api\"]}]"));
  }
}
