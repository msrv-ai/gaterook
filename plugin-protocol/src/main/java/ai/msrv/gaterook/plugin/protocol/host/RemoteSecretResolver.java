package ai.msrv.gaterook.plugin.protocol.host;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import ai.msrv.gaterook.plugin.protocol.SecretWire;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@link SecretResolver} a plugin gets for one call (report 16 §5): every question goes back to the core over
 * GPP/1 with the call's token, so the plugin gets only what it asks for, only while the call runs, and only fields of
 * its own connection — the core decides, this side only asks. Every value handed out is remembered and zeroed by
 * {@link #close()}, which the host calls when the call ends even if the plugin forgot.
 */
public final class RemoteSecretResolver implements SecretResolver, AutoCloseable {

  /** Vault may take this long to answer (report 16 §4.3). */
  static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final JsonRpcPeer peer;
  private final String callToken;
  private final List<AutoCloseable> issued = new ArrayList<>();
  private volatile boolean closed;

  public RemoteSecretResolver(JsonRpcPeer peer, String callToken) {
    this.peer = peer;
    this.callToken = callToken;
  }

  @Override
  public Secret secret(String field) throws SecretUnavailableException {
    check();
    WireSecret s = ask(peer.request(Gpp.SECRETS_GET, Messages.secretField(callToken, field),
        SecretWire.secretDecoder()), field);
    remember(s);
    return s;
  }

  @Override
  public Login login(String field) throws SecretUnavailableException {
    check();
    SecretWire.Login l = ask(peer.request(Gpp.SECRETS_LOGIN, Messages.secretField(callToken, field),
        SecretWire.loginDecoder()), field);
    remember(l.password());
    return new Login(l.username(), l.password(), l.password().version(), l.password().notAfter().orElse(null));
  }

  @Override
  public boolean isSet(String field) {
    JsonNode m = metaNode(field);
    return m != null && m.path("set").asBoolean(false);
  }

  @Override
  public Meta meta(String field) {
    JsonNode m = metaNode(field);
    if (m == null) {
      return new Meta(null, null, null);
    }
    String notAfter = m.path("notAfter").asString(null);
    return new Meta(m.path("source").asString(null), m.path("version").asString(null),
        notAfter == null ? null : Instant.parse(notAfter));
  }

  private JsonNode metaNode(String field) {
    if (closed) {
      return null;
    }
    try {
      return ask(peer.request(Gpp.SECRETS_META, Messages.secretField(callToken, field)), field);
    } catch (SecretUnavailableException e) {
      return null;
    }
  }

  @Override
  public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
      throws SecretUnavailableException {
    check();
    ObjectNode params = Messages.secretField(callToken, field);
    params.put("publicKey", Base64.getEncoder().encodeToString(key.getEncoded()));
    params.put("algorithm", key.getAlgorithm());
    params.put("principal", request.principal());
    params.put("ttlSeconds", request.ttl() == null ? 0 : request.ttl().toSeconds());
    if (request.keyId() != null) {
      params.put("keyId", request.keyId());
    }
    ObjectNode ext = params.putObject("extensions");
    if (request.extensions() != null) {
      request.extensions().forEach(ext::put);
    }
    JsonNode r = ask(peer.request(Gpp.SECRETS_SSH_CERTIFICATE, params), field);
    String notAfter = r.path("notAfter").asString(null);
    return new SshCertificate(r.path("certificate").asString(), r.path("serial").asString(null),
        notAfter == null ? null : Instant.parse(notAfter));
  }

  /** Zeroes every value handed out during the call; later questions fail. */
  @Override
  public void close() {
    closed = true;
    synchronized (issued) {
      for (AutoCloseable s : issued) {
        try {
          s.close();
        } catch (Exception ignored) {
          // Zeroing doesn't fail.
        }
      }
      issued.clear();
    }
  }

  private void remember(AutoCloseable s) {
    synchronized (issued) {
      if (closed) {
        try {
          s.close();
        } catch (Exception ignored) {
          // Zeroing doesn't fail.
        }
        return;
      }
      issued.add(s);
    }
  }

  private void check() throws SecretUnavailableException {
    if (closed) {
      throw new SecretUnavailableException("The call ended: its secrets are gone", null);
    }
  }

  private static <T> T ask(JsonRpcPeer.Call<T> call, String field) throws SecretUnavailableException {
    try {
      return call.get(TIMEOUT);
    } catch (RpcException e) {
      throw new SecretUnavailableException(e.getMessage(), null);
    } catch (TimeoutException e) {
      throw new SecretUnavailableException("GateRook didn't hand out " + field + " in time", null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SecretUnavailableException("The call was cancelled", e);
    } catch (JsonRpcPeer.PeerClosedException e) {
      throw new SecretUnavailableException("GateRook is gone", e);
    }
  }

  @Override
  public String toString() {
    return "RemoteSecretResolver[‹call›]";
  }
}
