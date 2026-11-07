package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import java.security.PublicKey;
import org.junit.jupiter.api.Test;

/** The login of a call: the typed ACL user with the stored password, or both from the store that holds them. */
class CredentialsTest {

  @Test
  void anAclUserTheStoreHoldsComesWithThePasswordFromTheLogin() throws Exception {
    RedisSettings s = RedisSettings.parse(Configs.redis("cache.dev.internal", 6379).build());
    assertThat(s.user()).isNull();
    StoreLogin secrets = new StoreLogin();
    try (Credentials c = Credentials.resolve(s, secrets)) {
      assertThat(c.user()).isEqualTo("cache_reader");
      assertThat(new String(c.password())).isEqualTo("pw-from-vault-2");
      assertThat(c.version()).isEqualTo("v2");
    }
    assertThat(secrets.logins).isEqualTo(1);
  }

  @Test
  void aTypedAclUserKeepsReadingThePasswordAlone() throws Exception {
    RedisSettings s = RedisSettings.parse(Configs.redis("cache.dev.internal", 6379).user("app").build());
    try (Credentials c = Credentials.resolve(s, TestSecrets.password("typed-pw"))) {
      assertThat(c.user()).isEqualTo("app");
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
      if (!field.equals(RedisSettings.PASSWORD)) {
        throw new SecretUnavailableException(field + " isn't the password", null);
      }
      logins++;
      // The password of the login, as a store hands it out.
      return new TestSecrets().dynamic("cache_reader", "pw-from-vault-2", "v2").login(field);
    }

    @Override
    public boolean isSet(String field) {
      return field.equals(RedisSettings.USER) || field.equals(RedisSettings.PASSWORD);
    }

    @Override
    public Meta meta(String field) {
      return new Meta("reference", "v2", null);
    }

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request) {
      throw new UnsupportedOperationException();
    }
  }
}
