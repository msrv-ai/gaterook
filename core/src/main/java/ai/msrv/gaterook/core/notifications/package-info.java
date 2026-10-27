/**
 * Notifications module (ARCHITECTURE.md §2.2). NotifyDispatcher, desktop events, Slack (SlackApi, SlackSocket,
 * SlackInteractions), Telegram (TelegramPoller, TgText), Eligibility (report 13 §6.2), links, pairing and
 * deliveries.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Notifications", allowedDependencies = {
        "platform", "settings", "secrets", "approvals", "policy"})
package ai.msrv.gaterook.core.notifications;

import org.springframework.modulith.ApplicationModule;
