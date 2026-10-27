package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdsTest {

  @Test
  void prefixedIdsHaveTheApprovalHandleShape() {
    String id = Ids.next("ap_");

    assertThat(id).matches("ap_[a-z0-9]{12}");
    assertThat(Ids.matches("ap_", id)).isTrue();
    assertThat(Ids.matches("ap_", id.toUpperCase())).isFalse();
    assertThat(Ids.matches("gr_", id)).isFalse();
  }

  @Test
  void idsDoNotRepeat() {
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      ids.add(Ids.next("tc_"));
    }
    assertThat(ids).hasSize(10_000);
  }

  @Test
  void prefixesAreShortLowerCaseWords() {
    assertThatThrownBy(() -> Ids.next("AP_")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Ids.next("ap")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Ids.next("toolong_")).isInstanceOf(IllegalArgumentException.class);
    assertThat(Ids.uuid()).hasSize(36);
  }
}
