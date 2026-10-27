package ai.msrv.gaterook.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretKind;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What the resolver puts into the call's taint, for values the database can't easily hold here (binary ones). */
class ScopedSecretResolverTest {

  @Test
  void aBinarySecretTaintsItsEncodings() throws Exception {
    byte[] keytab = new byte[42];
    new Random(11).nextBytes(keytab);
    keytab[0] = (byte) 0xC3;
    keytab[1] = (byte) 0x28; // an invalid UTF-8 sequence: the value has no text view
    SecretService service = mock(SecretService.class);
    SecretOwner owner = SecretOwner.connection("kerberos-api");
    when(service.resolve(eq(owner), eq("/credentials/keytab"), any(), any(), any())).thenAnswer(invocation ->
        new SecretService.Resolved(SecretKind.LOCAL, "kerberos-api/keytab", "v1", keytab.clone(), null));
    Taint taint = new Taint();

    try (ScopedSecretResolver resolver = new ScopedSecretResolver(service, owner, Set.of("/credentials/keytab"),
        taint, ScopedSecretResolver.Scope.NONE)) {
      assertThat(resolver.secret("/credentials/keytab").chars()).isEmpty();
    }

    String echo = "keytab " + Base64.getEncoder().encodeToString(keytab) + " / "
        + HexFormat.of().formatHex(keytab);
    assertThat(Masker.standard().text(echo, taint, MaskPolicy.SECRETS_ONLY).text())
        .isEqualTo("keytab " + Masker.MASK + " / " + Masker.MASK);
  }
}
