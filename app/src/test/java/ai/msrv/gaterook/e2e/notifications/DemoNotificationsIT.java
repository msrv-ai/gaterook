package ai.msrv.gaterook.e2e.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.notifications.NotificationService;
import ai.msrv.gaterook.core.notifications.NotificationViews;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The demo world's notifications (design/BLUEPRINT.md §9, step K2) on the real built-in connectors: desktop
 * notifications on, Slack shown connected to {@code acme} posting to {@code #deploy-approvals} with no token stored,
 * Telegram not set up; the demo's waiting requests are announced on the desktop only — its Slack never calls Slack.
 */
class DemoNotificationsIT {

  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("demo-notify-it-launch-secret-001".getBytes(StandardCharsets.US_ASCII));

  @Test
  void theDemoShowsTheKitsChannelsAndNeverCallsSlack() throws InterruptedException {
    Path home = newHome();
    try (ConfigurableApplicationContext core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s", "--gateway.demo=true")) {
      NotificationViews.Overview overview = core.getBean(NotificationService.class).overview();

      NotificationViews.Channel desktop = channel(overview, "desktop");
      assertThat(desktop.enabled()).isTrue();
      assertThat(desktop.state()).isEqualTo("ok");
      assertThat(desktop.config().path("soundForProd").asBoolean()).isTrue();
      assertThat(desktop.config().path("maxBurst").asInt()).isEqualTo(3);

      NotificationViews.Channel slack = channel(overview, "slack");
      assertThat(slack.enabled()).isTrue();
      assertThat(slack.verified()).isTrue();
      assertThat(slack.state()).isEqualTo("connected");
      assertThat(slack.stateDetail()).isEqualTo("Connected · workspace acme");
      JsonNode config = slack.config();
      assertThat(config.path("deliverTo").asString()).isEqualTo("channel");
      assertThat(config.path("channelName").asString()).isEqualTo("#deploy-approvals");
      assertThat(config.path("workspace").path("team").asString()).isEqualTo("acme");
      assertThat(config.has("demo")).as("the demo marker stays inside the core").isFalse();
      assertThat(slack.tokens()).isEmpty();
      assertThat(slack.lastDeliveryAt()).isBetween(Instant.now().minus(Duration.ofMinutes(30)),
          Instant.now().minus(Duration.ofMinutes(27)));

      assertThat(channel(overview, "telegram").state()).isEqualTo("off");
      assertThat(overview.links()).isEmpty();

      // The waiting requests reach the desktop; the demo's Slack has no tokens and isn't asked.
      ApprovalService approvals = core.getBean(ApprovalService.class);
      List<ApprovalView> pending = approvals.pending();
      assertThat(pending).isNotEmpty();
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (pending.stream().anyMatch(a -> channels(approvals, a.id()).isEmpty()) && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      for (ApprovalView a : pending) {
        assertThat(channels(approvals, a.id())).as(a.id()).containsExactly("desktop");
      }
    }
  }

  private static List<String> channels(ApprovalService approvals, String id) {
    return approvals.timeline(id).stream().filter(e -> "notified".equals(e.kind()))
        .map(e -> e.detail().path("channel").asString()).toList();
  }

  private static NotificationViews.Channel channel(NotificationViews.Overview o, String kind) {
    return o.channels().stream().filter(c -> c.kind().equals(kind)).findFirst().orElseThrow();
  }

  private static Path newHome() {
    try {
      Path parent = Files.createDirectories(Path.of("target", "gateway-homes").toAbsolutePath());
      return Files.createTempDirectory(parent, "demo-notify-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
