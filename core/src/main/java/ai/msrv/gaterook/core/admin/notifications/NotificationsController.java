package ai.msrv.gaterook.core.admin.notifications;

import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.core.admin.ApiProblem;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.notifications.NotificationService;
import ai.msrv.gaterook.core.notifications.NotificationViews;
import ai.msrv.gaterook.core.notifications.NotifyTestFailedException;
import java.util.List;
import java.util.Locale;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Settings › Notifications (ARCHITECTURE.md §6 settings, kit {@code settings.html#settings-notifications}): desktop,
 * Slack and Telegram, their tests, the test message, linked accounts. Tokens arrive in a {@code PUT} and never leave:
 * answers show what is stored for them, not their value. {@code POST /internal/notifications/desktop} is Electron
 * main's report on OS notifications (only main reaches {@code /internal/**}).
 */
@RestController
@RegisterReflectionForBinding({NotificationViews.Overview.class, NotificationViews.Channel.class,
    NotificationViews.StoredToken.class, NotificationViews.Link.class, NotificationViews.Pairing.class,
    NotificationViews.Sent.class, NotificationViews.SentTo.class, NotificationViews.SlackChannelOption.class,
    NotificationsController.ChannelBody.class, NotificationsController.LinkBody.class,
    NotificationsController.ReasonBody.class, NotificationsController.DesktopReport.class,
    NotificationsController.Outcome.class, NotificationsController.Hint.class, NotificationsController.Check.class,
    NotificationsController.Failure.class, NotificationsController.Confirmation.class,
    NotificationsController.TestProblem.class})
class NotificationsController {

  /**
   * {@code PUT /api/notifications/{channel}}: the fields of that channel; a missing field keeps its value, a token is
   * replaced only when it is sent.
   *
   * @param version the {@code version} the form read ({@code 0} for a channel never saved)
   * @param confirm {@code remove-webhook}: the person agreed to remove the Telegram bot's webhook
   */
  record ChannelBody(Boolean enabled, Boolean soundForProd, Integer maxBurst, String deliverTo, String channelId,
      char[] botToken, char[] appToken, Boolean protectContent, Boolean confirmApprove, String confirm, Long version,
      String reason) {}

  record LinkBody(String email, String reason) {}

  record ReasonBody(String reason) {}

  /** {@code event}: {@code shown}, {@code failed} (with what the OS said) or {@code unsupported}. */
  record DesktopReport(String event, String detail) {}

  /** A test, in the shape of the connection form's (connections.api {@code TestOutcome}). */
  record Outcome(boolean ok, long latencyMs, String serverVersion, String identity, Object permissions,
      List<Hint> hints, List<Check> checks, Failure error, Confirmation needsConfirmation, String confirmationId,
      JsonNode suggestedPolicyChange, JsonNode details) {}

  record Hint(String severity, String code, String field, String text, Object snippet) {}

  record Check(String name, boolean ok, long ms, String detail) {}

  record Failure(String code, String message, String technical) {}

  /** {@code remove-webhook}: repeat the PUT with {@code confirm: "remove-webhook"}. */
  record Confirmation(String kind, String text, JsonNode data) {}

  /** 422 of a PUT whose tokens failed their test: the problem and the test. */
  record TestProblem(String type, String title, String detail, String field, List<ApiProblem.FieldError> errors,
      String confirm, Outcome test) {}

  private final NotificationService notifications;

  NotificationsController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping("/api/notifications")
  NotificationViews.Overview overview() {
    return notifications.overview();
  }

  @PutMapping("/api/notifications/{channel}")
  NotificationViews.Channel update(@PathVariable String channel, @RequestBody ChannelBody body)
      throws InterruptedException {
    String reason = Reasons.check(body.reason());
    long version = body.version() == null ? notifications.channel(channel).version() : body.version();
    return switch (channel) {
      case "desktop" -> notifications.updateDesktop(new NotificationService.DesktopUpdate(body.enabled(),
          body.soundForProd(), body.maxBurst()), version, reason);
      case "slack" -> notifications.updateSlack(new NotificationService.SlackUpdate(body.enabled(), body.deliverTo(),
          body.channelId(), body.botToken(), body.appToken()), version, reason);
      case "telegram" -> notifications.updateTelegram(new NotificationService.TelegramUpdate(body.enabled(),
          body.protectContent(), body.confirmApprove(), body.botToken(), "remove-webhook".equals(body.confirm())),
          version, reason);
      default -> throw ai.msrv.gaterook.core.platform.ProblemException.notFound("No such channel", "The channels are desktop, slack and "
          + "telegram.");
    };
  }

  @PostMapping("/api/notifications/{channel}/test")
  Outcome test(@PathVariable String channel) throws InterruptedException {
    if (!"slack".equals(channel) && !"telegram".equals(channel)) {
      throw ai.msrv.gaterook.core.platform.ProblemException.notFound("No such test", "Slack and Telegram have a test; desktop notifications "
          + "use Send test notification.");
    }
    return outcome(notifications.test(channel));
  }

  @PostMapping("/api/notifications/test")
  NotificationViews.Sent sendTest() throws InterruptedException {
    return notifications.sendTest();
  }

  @GetMapping("/api/notifications/slack/channels")
  List<NotificationViews.SlackChannelOption> slackChannels() throws InterruptedException {
    return notifications.slackChannels();
  }

  @PostMapping("/api/notifications/slack/links")
  @ResponseStatus(HttpStatus.CREATED)
  NotificationViews.Link linkSlack(@RequestBody LinkBody body) throws InterruptedException {
    Reasons.check(body.reason());
    return notifications.linkSlack(body.email());
  }

  @PostMapping("/api/notifications/telegram/pairing")
  @ResponseStatus(HttpStatus.CREATED)
  NotificationViews.Pairing telegramPairing() {
    return notifications.telegramPairing();
  }

  @PostMapping("/api/notifications/telegram/pairing/{id}/confirm")
  NotificationViews.Link confirmTelegram(@PathVariable String id,
      @RequestBody(required = false) ReasonBody body) {
    return notifications.confirmTelegramPairing(id, Reasons.check(body == null ? null : body.reason()));
  }

  @DeleteMapping("/api/notifications/links/{id}")
  ResponseEntity<Void> deleteLink(@PathVariable String id, @RequestBody(required = false) ReasonBody body) {
    notifications.deleteLink(id, Reasons.check(body == null ? null : body.reason()));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/api/notifications/{channel}")
  ResponseEntity<Void> disconnect(@PathVariable String channel, @RequestBody(required = false) ReasonBody body) {
    notifications.disconnect(channel.toLowerCase(Locale.ROOT), Reasons.check(body == null ? null : body.reason()));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/internal/notifications/desktop")
  ResponseEntity<Void> desktopReport(@RequestBody DesktopReport body) {
    notifications.desktopReport(body.event(), body.detail());
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(NotifyTestFailedException.class)
  ResponseEntity<TestProblem> testFailed(NotifyTestFailedException e) {
    TestResult r = e.result();
    boolean confirm = r.needsConfirmation() != null;
    String field = r.hints().stream().filter(h -> h.field() != null).map(TestResult.Hint::field).findFirst()
        .orElse(null);
    TestProblem body = new TestProblem("about:blank", confirm ? "Confirm first" : "The test failed",
        e.getMessage(), field, field == null ? List.of() : List.of(new ApiProblem.FieldError(field, e.getMessage())),
        confirm ? r.needsConfirmation().kind() : null, outcome(r));
    return ResponseEntity.unprocessableContent().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  static Outcome outcome(TestResult r) {
    return new Outcome(r.ok(), r.latencyMs(), r.serverVersion(), r.identity(), null,
        r.hints().stream().map(h -> new Hint(h.severity().name().toLowerCase(Locale.ROOT), h.code(), h.field(),
            h.text(), null)).toList(),
        r.checks().stream().map(c -> new Check(c.name(), c.ok(), c.ms(), c.detail())).toList(),
        r.error() == null ? null : new Failure(r.error().code(), r.error().message(), r.error().technical()),
        r.needsConfirmation() == null ? null : new Confirmation(r.needsConfirmation().kind(),
            r.needsConfirmation().text(), r.needsConfirmation().data()), null, null, r.details());
  }
}
