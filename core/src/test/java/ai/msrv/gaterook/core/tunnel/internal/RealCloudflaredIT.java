package ai.msrv.gaterook.core.tunnel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The real {@code cloudflared} 2026.10.0 (profile {@code network}, report 02 §8): the pinned release downloaded and
 * hash-checked into {@code ~/.cache/mcp-gateway-test/k3-cloudflared}, started with our argv for a quick tunnel.
 * It must get a {@code trycloudflare.com} hostname. Where the edge is out of reach (this sandbox: no SRV for
 * {@code _v2-origintunneld._tcp.argotunnel.com}, port 7844 closed) the start fails with the person's hint — after
 * the 30 s ready wait, or sooner when cloudflared gives up on edge discovery itself — and the agent is gone; with a
 * working edge the tunnel is up and is stopped again.
 */
@Tag("network")
class RealCloudflaredIT {

  @Test
  void theRealAgentGetsAQuickTunnelHostnameAndIsStoppedEitherWay() throws Exception {
    Path cache = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test", "k3-cloudflared");
    TunnelBinaries binaries = new TunnelBinaries(cache, "", "");
    Path binary = binaries.cloudflared();
    assertThat(binaries.cloudflaredStatus().sha256Ok()).isTrue();

    Path home = TestGateway.newHome("real-cloudflared");
    Path run = Files.createDirectories(home.resolve("run"));
    Path logs = Files.createDirectories(home.resolve("logs"));
    TunnelAgent.StartRequest request = new TunnelAgent.StartRequest(TunnelProvider.CLOUDFLARE_QUICK, binary,
        TunnelAgent.freeLoopbackPort(), null, null, run, logs);
    TunnelAgent.Started started = null;
    String failure = null;
    try {
      started = new CloudflaredAgent().start(request);
    } catch (TunnelException e) {
      failure = e.getMessage();
    }
    String log = Files.readString(logs.resolve("cloudflared.log"));
    if (started != null) {
      try {
        assertThat(started.host()).endsWith(".trycloudflare.com");
      } finally {
        started.process().stop();
      }
    } else {
      assertThat(log).as("the hostname came from Cloudflare first").contains(".trycloudflare.com");
      // Either the ready wait ran out, or cloudflared gave up on finding its edge: both say what to check.
      assertThat(failure).containsAnyOf("port 7844", "Can't reach Cloudflare's edge");
      assertThat(failure).contains("firewall");
    }
    assertThat(run.resolve("tunnel.pid")).doesNotExist();
    String config = run.resolve("cloudflared.yml").toString();
    assertThat(ProcessHandle.allProcesses().filter(h -> h.info().arguments().map(a -> Arrays.asList(a)
        .contains(config)).orElse(false))).as("no agent of this test left").isEmpty();
  }
}
