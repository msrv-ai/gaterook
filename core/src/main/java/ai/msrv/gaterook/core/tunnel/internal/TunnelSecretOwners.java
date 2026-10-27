package ai.msrv.gaterook.core.tunnel.internal;

import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretOwners;
import ai.msrv.gaterook.core.tunnel.TunnelSettings;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What Secrets shows for the tunnel's tokens ({@code secret.owner_kind = 'tunnel'}): the named tunnel's
 * {@code TUNNEL_TOKEN} and ngrok's authtoken. They have no environment, nothing tests them before Replace, and neither
 * provider issues a new one through the gateway (Rotate stays off with its reason).
 */
@Component
class TunnelSecretOwners implements SecretOwners {

  @Override
  public SecretOwner.Kind kind() {
    return SecretOwner.Kind.TUNNEL;
  }

  @Override
  public Optional<Owner> describe(String ownerId) {
    if (!TunnelSettings.SECRET_OWNER.equals(ownerId)) {
      return Optional.empty();
    }
    return Optional.of(new Owner("tunnel", null, List.of("Settings › Tunnel")));
  }

  @Override
  public Optional<String> rotateUnavailable(String ownerId, String field) {
    return Optional.of(TunnelSettings.NGROK_AUTHTOKEN_FIELD.equals(field)
        ? "ngrok issues authtokens in its dashboard. Replace it with a new one."
        : "Cloudflare issues tunnel tokens in Zero Trust. Replace it with a new one.");
  }
}
