package com.gaterook.core.oauth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.core.oauth.internal.persistence.OAuthClientKind;
import com.gaterook.core.platform.Ingress;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Who a waiting sign-in names on the browser's page: the app on this computer, the host through the tunnel. */
class ConsentNameTest {

  private static final Issuers.Server LOCAL = new Issuers.Server(Ingress.LOCAL, "http://127.0.0.1:7465", null);
  private static final Issuers.Server TUNNEL = new Issuers.Server(Ingress.TUNNEL,
      "https://gw-smoke-7f3k.trycloudflare.com", null);

  @Test
  void aLocalAppIsTheNameItRegisteredWithNotItsFirstCallbackHost() {
    // VS Code desktop registers these callbacks in this order, so its host is insiders.vscode.dev for every VS Code.
    Clients.Client vsCode = new Clients.Client("row-1", "gwc_vscode", OAuthClientKind.DCR, Ingress.LOCAL,
        "insiders.vscode.dev", "Visual Studio Code", List.of("https://insiders.vscode.dev/redirect",
            "https://vscode.dev/redirect", "http://127.0.0.1/", "http://127.0.0.1:33418/"));

    assertThat(OAuthServer.consentName(LOCAL, vsCode)).isEqualTo("Visual Studio Code");
  }

  @Test
  void aLocalAppWithoutANameStaysItsHost() {
    Clients.Client unnamed = new Clients.Client("row-2", "gwc_unnamed", OAuthClientKind.DCR, Ingress.LOCAL,
        "127.0.0.1", " ", List.of("http://127.0.0.1:43123/callback"));

    assertThat(OAuthServer.consentName(LOCAL, unnamed)).isEqualTo("127.0.0.1");
  }

  @Test
  void throughTheTunnelAClientStaysTheHostItsRedirectVouchesFor() {
    // Anyone with the public URL can register any name; the dialog keeps the allowed redirect's host.
    Clients.Client registered = new Clients.Client("row-3", "gwc_remote", OAuthClientKind.DCR, Ingress.TUNNEL,
        "chatgpt.com", "Visual Studio Code", List.of("https://chatgpt.com/connector_platform_oauth_redirect"));
    Clients.Client claude = new Clients.Client("row-4", "https://claude.ai/oauth/mcp-oauth-client-metadata",
        OAuthClientKind.CIMD, Ingress.TUNNEL, "claude.ai", "Claude",
        List.of("https://claude.ai/api/mcp/auth_callback"));

    assertThat(OAuthServer.consentName(TUNNEL, registered)).isEqualTo("chatgpt.com");
    assertThat(OAuthServer.consentName(TUNNEL, claude)).isEqualTo("claude.ai");
  }
}
