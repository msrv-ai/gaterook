package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.SecretUnavailableException;
import java.security.PublicKey;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The login of a call: the typed user with the stored password, or both from the store that holds the login. */
class CredentialsTest {

  private static ConnectionConfig cfg(String credentials) {
    return new ConnectionConfig("c-orders", "orders-db", "postgres", Env.DEV, 3, JsonMapper.builder().build()
        .readTree("{\"connection\":{\"host\":\"db.dev.internal\",\"port\":5432},\"credentials\":" + credentials + "}"),
        Set.of(ConnectionSettings.PASSWORD), null);
  }

  @Test
  void aUserTheStoreHoldsComesWithThePasswordFromTheLogin() throws Exception {
    StoreLogin secrets = new StoreLogin();
    try (Credentials c = Credentials.resolve(cfg("{}"), secrets)) {
      assertThat(c.user()).isEqualTo("app_vault");
      assertThat(new String(c.password())).isEqualTo("pw-from-vault-4");
      assertThat(c.version()).isEqualTo("v4");
    }
    assertThat(secrets.logins).isEqualTo(1);
  }

  @Test
  void aTypedUserKeepsReadingThePasswordAlone() throws Exception {
    try (Credentials c = Credentials.resolve(cfg("{\"user\":\"app_rw\"}"), Fixtures.Secrets.password("typed-pw"))) {
      assertThat(c.user()).isEqualTo("app_rw");
      assertThat(new String(c.password())).isEqualTo("typed-pw");
    }
  }

  /** {@code /credentials/user} and {@code /credentials/password} from one Vault secret. */
  static final class StoreLogin implements SecretResolver {
    int logins;

    @Override
    public Secret secret(String field) {
      throw new AssertionError(field + " was read alone; its login holds the user");
    }

    @Override
    public Login login(String field) throws SecretUnavailableException {
      if (!field.equals(ConnectionSettings.PASSWORD)) {
        throw new SecretUnavailableException(field + " isn't the password", null);
      }
      logins++;
      return Fixtures.Secrets.login("app_vault", "pw-from-vault-4", "v4", null);
    }

    @Override
    public boolean isSet(String field) {
      return field.equals(ConnectionSettings.USER) || field.equals(ConnectionSettings.PASSWORD);
    }

    @Override
    public Meta meta(String field) {
      return new Meta("reference", "v4", null);
    }

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request) {
      throw new UnsupportedOperationException();
    }
  }
}
