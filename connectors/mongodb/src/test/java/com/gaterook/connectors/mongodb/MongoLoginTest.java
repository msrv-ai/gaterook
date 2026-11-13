package com.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.SecretUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The login of a call: the typed user with the stored password, or both from the store that holds them. */
class MongoLoginTest {

  private static MongoSettings settings(String credentials) {
    return MongoSettings.of(Fixtures.config(Env.DEV, """
        {"connection":{"mode":"host","host":"mongo.dev.internal","port":27017,"databases":["orders"]},
         "credentials":%s}""".formatted(credentials)));
  }

  @Test
  void aUserTheStoreHoldsComesWithThePasswordFromTheLogin() throws Exception {
    MongoSettings s = settings("{\"authSource\":\"admin\"}");
    assertThat(s.user()).isNull();
    StoreLogin secrets = new StoreLogin();
    try (MongoLogin login = MongoLogin.resolve(s, secrets)) {
      assertThat(login.user()).isEqualTo("orders_reader");
      assertThat(new String(login.password())).isEqualTo("pw-from-vault-3");
      assertThat(login.version()).isEqualTo("v3");
      assertThat(login.identity(s)).isEqualTo("orders_reader@admin");
    }
    assertThat(secrets.logins).isEqualTo(1);
  }

  @Test
  void aTypedUserKeepsReadingThePasswordAlone() throws Exception {
    MongoSettings s = settings("{\"user\":\"reader\"}");
    try (MongoLogin login = MongoLogin.resolve(s, Fixtures.Secrets.password("typed-pw"))) {
      assertThat(login.user()).isEqualTo("reader");
      assertThat(new String(login.password())).isEqualTo("typed-pw");
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
      if (!field.equals(MongoSettings.PASSWORD)) {
        throw new SecretUnavailableException(field + " isn't the password", null);
      }
      logins++;
      char[] chars = "pw-from-vault-3".toCharArray();
      Secret password = new Secret() {
        @Override
        public char[] chars() {
          return chars;
        }

        @Override
        public byte[] bytes() {
          return new String(chars).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String unsafeString() {
          return new String(chars);
        }

        @Override
        public String version() {
          return "v3";
        }

        @Override
        public Optional<Instant> notAfter() {
          return Optional.empty();
        }

        @Override
        public void close() {
          java.util.Arrays.fill(chars, '\0');
        }
      };
      return new Login("orders_reader", password, "v3", null);
    }

    @Override
    public boolean isSet(String field) {
      return field.equals(MongoSettings.USER) || field.equals(MongoSettings.PASSWORD);
    }

    @Override
    public Meta meta(String field) {
      return new Meta("reference", "v3", null);
    }

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request) {
      throw new UnsupportedOperationException();
    }
  }
}
