package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.connector.api.ExecContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Opaque handles connectors give agents (ARCHITECTURE.md §3 {@code ExecContext.handles()}): a cursor of
 * {@code logs_tail}, a scan cursor of Redis. {@code base64url(JSON).base64url(HMAC)} under the {@code handles/v1} key:
 * the agent can carry one back, but can't forge or edit one, nor reuse it for another purpose or after it expired.
 * Connectors put their own short prefix in front ({@code lc1.}); the JSON part starts with {@code eyJ}, which is how
 * the masker's entropy check recognises the gateway's own handles.
 */
public final class SealedHandles implements ExecContext.HandleCodec {

  /** Handles live at most this long, whatever a connector asks. */
  public static final Duration MAX_TTL = Duration.ofDays(1);

  private static final Pattern PURPOSE = Pattern.compile("[a-z][a-z0-9.-]{0,47}");
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final HmacKeys keys;
  private final JsonMapper json;
  private final Clock clock;

  public SealedHandles(HmacKeys keys, JsonMapper json, Clock clock) {
    this.keys = Objects.requireNonNull(keys, "keys");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public String seal(String purpose, JsonNode payload, Duration ttl) {
    checkPurpose(purpose);
    Duration life = ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
    ObjectNode body = json.createObjectNode();
    body.put("p", purpose);
    body.put("x", clock.instant().plus(life).getEpochSecond());
    body.set("d", payload == null ? json.nullNode() : payload);
    byte[] text = json.writeValueAsBytes(body);
    return ENCODER.encodeToString(text) + "." + ENCODER.encodeToString(keys.mac(HmacKeys.Purpose.HANDLES, text));
  }

  @Override
  public Optional<JsonNode> open(String purpose, String handle) {
    checkPurpose(purpose);
    if (handle == null || handle.length() > 16_384) {
      return Optional.empty();
    }
    int dot = handle.indexOf('.');
    if (dot <= 0 || dot != handle.lastIndexOf('.')) {
      return Optional.empty();
    }
    try {
      byte[] text = DECODER.decode(handle.substring(0, dot));
      byte[] mac = DECODER.decode(handle.substring(dot + 1));
      if (!keys.verify(HmacKeys.Purpose.HANDLES, text, mac)) {
        return Optional.empty();
      }
      JsonNode body = json.readTree(new String(text, StandardCharsets.UTF_8));
      if (!purpose.equals(body.path("p").asString(null))
          || body.path("x").asLong(0) < clock.instant().getEpochSecond()) {
        return Optional.empty();
      }
      return Optional.of(body.path("d"));
    } catch (IllegalArgumentException | JacksonException | IllegalStateException e) {
      // Not base64, not JSON, or a locked core: to the agent every bad handle looks the same.
      return Optional.empty();
    }
  }

  private static void checkPurpose(String purpose) {
    if (purpose == null || !PURPOSE.matcher(purpose).matches()) {
      throw new IllegalArgumentException("Invalid handle purpose: " + purpose);
    }
  }
}
