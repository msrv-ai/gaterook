package ai.msrv.gaterook.core.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The account that presented a pairing code, as the app shows it for the person's confirmation (K2 review, finding 2):
 * the confirmation is what stops a leaked {@code t.me/<bot>?start=<code>} link, so a name — anyone's to choose — can't
 * pass for the person's own {@code @username}.
 */
class CandidateDisplayTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static String display(String from) {
    return TelegramChannel.display(JSON.readTree(from));
  }

  @Test
  void aUsernameIsShownWithItsName() {
    assertThat(display("{\"id\":424242,\"username\":\"ravi\",\"first_name\":\"Ravi\",\"last_name\":\"T.\"}"))
        .isEqualTo("@ravi (Ravi T.)");
    assertThat(display("{\"id\":424242,\"username\":\"ravi_t\"}")).isEqualTo("@ravi_t");
  }

  @Test
  void aNameThatImitatesAnAccountShowsAsWhatItIs() {
    // No username, a name written as Ravi's account: before, the app showed exactly "@ravi (Ravi T.)".
    String eve = display("{\"id\":999001,\"first_name\":\"@ravi\",\"last_name\":\"(Ravi T.)\"}");
    assertThat(eve).isEqualTo("no username · id 999001 · @ravi (Ravi T.)").doesNotStartWith("@");
    assertThat(display("{\"id\":999002,\"first_name\":\"" + "x".repeat(200) + "\"}"))
        .as("the marker comes first, a cut name never loses it").startsWith("no username · id 999002");
    assertThat(display("{\"id\":999003}")).isEqualTo("no username · id 999003");
  }

  @Test
  void hiddenCharactersShowAsEscapesAndBadUsernamesDontCount() {
    String rtl = display("{\"id\":999004,\"first_name\":\"ivar\\u202E\",\"username\":\"ravi@\"}");
    assertThat(rtl).isEqualTo("no username · id 999004 · ivar‹U+202E›");
    JsonNode from = JSON.readTree("{\"id\":424242,\"username\":\"ravi\",\"first_name\":\"Ravi\\u200B\"}");
    assertThat(TelegramChannel.display(from)).isEqualTo("@ravi (Ravi‹U+200B›)");
  }
}
