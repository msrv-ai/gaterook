# 13 · Approval notifications: desktop, Slack, and Telegram

Research date: **2026-10-23**. Scope: one local user and one desktop core. Historical evidence includes API/source inspection and mock Socket Mode/long-polling experiments; live authorized channel delivery and OS notifications were not comprehensively verified. See the [architecture](../ARCHITECTURE.md), [approval protocol](01-mcp-protocol.md), and [secret handling](12-secrets.md).

## 1. Selected transports

Use JDK HTTP/WebSocket and Jackson already available in the core. Electron uses its built-in Notification API and fetch. Avoid additional Slack/Telegram framework dependencies for the small supported API surface.

| Destination | Transport | Behavior |
| --- | --- | --- |
| Focused application | Core SSE through Electron main, then preload IPC | In-app review toast |
| Background/tray application | Electron Notification | Review and Deny, never Approve |
| Slack | Web API plus Socket Mode | Linked-account DM by default; reviewed channel optional |
| Telegram | Bot API HTTPS long polling | Linked private chat, plain text with entities |

The core owns the approval state machine; notifications only deliver requests and forward authenticated decisions to its public decision API. Channel failure neither approves nor blocks a request. The application remains the place to review pending requests, and expiry denies them.

The main process subscribes to `/api/events` with the per-launch internal secret. It forwards a filtered event surface to the renderer. Browser development can use the development proxy/EventSource path. SSE reconnection and heartbeat handling must not duplicate decisions.

Webhooks were rejected because the application is local and its public tunnel is restricted to MCP/OAuth. Process stdout was rejected as the notification bus because it complicates independent core development and mixes lifecycle/log concerns. Telegram entities avoid the ambiguity of Markdown/HTML escaping.

## 2. Exact action and eligibility

An OS notification is only a preview: its actions are **Review** and **Deny**. Deep links and Open in app only navigate; they never approve, connect, publish, or execute anything. An arbitrary website may invoke a registered application scheme, so the route must be strictly validated.

Channel Approve is available only when every condition holds **both when sending and when handling the click**:

1. Decisions from linked accounts are enabled.
2. The environment is not production, under the stricter design proposed here.
3. The policy does not require a comment.
4. The complete action fits the supported message budget: 3,000 UTF-16 units for the Slack action and 4,096 for the whole Telegram message.
5. The action contains no dangerous invisible/control/confusable formatting that needs the application's inspector.
6. Secret detection/masking has not hidden any part of the exact action.
7. The channel is not in flood-summary mode.
8. The request is pending and its action digest and recorded delivery identity match.

If any condition prevents complete review, omit Approve and explain why. Do not display a truncated action as if it were complete. When channel decisions are disabled entirely, show only Open in app; desktop Deny remains a separate supported action.

The original design kit allowed production channel approval if its comment requirement were disabled. This research proposed **never** allowing it on production; that historical discrepancy must be resolved through the architecture/policy implementation, not silently treated as a proven old behavior.

Never include the agent's note in a notification. Exclude that field from the notice DTO instead of relying on each formatter to remember to remove it. Show environment, connection, client, exact action, expiry, and relevant rule. Mark production in text, not color alone.

## 3. Slack connection and delivery

An app-level token with `connections:write` opens Socket Mode through `apps.connections.open`; the bot token calls Web API. After hello, acknowledge each envelope immediately, within Slack's three-second deadline, before any slow application work. Serialize WebSocket text sends: concurrent pending sends can fail in the JDK client. Reconnect with backoff and handle refresh/disabled-link events.

Use `users.lookupByEmail`, then `conversations.open`, and address the resulting DM ID. The reviewed scopes are `chat:write`, `im:write`, `users:read`, `users:read.email`, and channel-list scopes only where that UI is supported. The bot must be explicitly invited to a channel; do not ask for blanket public-channel posting access.

Link an account with a matching code shown in the app and a confirmation in that user's DM. Store team, user, and delivery identities. A Slack click is accepted only when team ID, app ID, linked user ID, channel ID, message timestamp, instance ID, approval ID, and digest match the stored delivery. Socket Mode authenticates the transport; these application-level checks still matter.

Deduplicate envelope IDs and use an atomic pending-to-decided transition. Repeated or racing clicks must produce one execution. Update the original message after resolution and remove action buttons; use fresh block IDs on updates. Foreign users receive a bounded ephemeral explanation.

Use a literal preformatted action block and plain-text headers. Escape mrkdwn in metadata fields. The original fact review did not confirm all literal-rendering edge cases in `rich_text_preformatted`; a live golden check for `&`, `<`, `>`, `*`, and backticks remains necessary. On invalid blocks, fall back to a summary without Approve.

Relevant Block Kit limits include header 150 characters, section text 3,000, button text 75, button value 2,000, IDs 255, and confirmation title/text 100/300. These are dated compatibility constraints and should remain covered by formatting tests.

## 4. Telegram pairing, polling, and actions

Use `getMe` and `getWebhookInfo` before enabling polling. Removing another webhook changes bot ownership behavior and requires explicit user confirmation. Advance `getUpdates.offset` to the last processed update ID plus one; retry transient failures with bounded backoff and respect retry-after responses.

Pair using a one-time random 16-byte base64url code, stored as a hash with a ten-minute TTL. Accept it only in a private chat, then require confirmation of the candidate account **inside the application**. Possession of a leaked pairing link alone must not link an account. Rate-limit invalid-code replies.

Send plain text with `entities`, not parse_mode. Java UTF-16 offsets match Telegram entity offsets; test surrogate pairs explicitly. Keep callback_data within 64 **bytes**. Use content protection and disable link previews, while recognizing these do not stop all copying or screenshots.

Treat callback_data as untrusted. Check linked sender, chat, message ID, approval ID, and digest against the recorded delivery. A two-step Approve first displays Confirm approve/Back and issues a short-lived nonce; only the second verified callback requests execution. Always call `answerCallbackQuery` to dismiss the client's progress indicator.

After resolution, edit the message and remove buttons. Error strings such as message-not-modified, query-too-old, and the claimed second-poller 409 behavior were not documented/independently established in the fact review; handle errors defensively rather than depending only on exact text.

## 5. Flooding, privacy, and secrets

More than five new requests per minute switches a channel to a digest with a count and Open in app, without Approve. Desktop bursts similarly collapse after a bounded number within ten seconds. This limits notification fatigue and rapid accidental approval.

Exact actions can contain customer or infrastructure data and leave the device through Slack/Telegram. Default to a DM/private chat. Channel configuration must state that every member can read exact actions. Users handling restricted data need an application-only/desktop-only option.

Store bot/app tokens through the secrets module. Telegram tokens occur in the URL path, so redact URLs before logging and disable verbose HTTP logging. Channel transport errors must never include tokens. Only enabled channels initiate external traffic.

One installation should use its own Slack application and Telegram bot. Slack can route an envelope to any active connection; an instance marker prevents one installation deciding another's request. Multiple Telegram pollers conflict operationally and need an actionable error.

Rotating Slack app-level tokens require refresh support; do not silently enable token rotation without implementing it. Enterprise Grid org installations, Telegram groups, channel comment-entry modals, and authenticated corporate WebSocket proxy behavior were outside or unresolved in this proposal.

## 6. Test connection and platform limits

Test Slack bot identity, app-level Socket Mode hello, scopes where observable, channel membership/privacy, and linked-account state. Test Telegram identity, webhook state, and pairing. Validate tokens before saving. A test message must say **Test: nothing will run**, and its buttons must never resolve real approvals.

Desktop support varies by OS and packaging. macOS notification delivery needs appropriate signing and alert configuration; Windows needs the installed application identity/shortcut; Linux action buttons require the notification server's actions capability. A click-to-review fallback is necessary. OS do-not-disturb can suppress notifications without proving a transport failure. Closing the window must keep the tray process alive if background notification delivery is expected.

## 7. Validation evidence

The recorded JDK 25/MockWebServer 5.5.0/JUnit 6.0.3 prototypes passed WebSocket hello-envelope-ack and Telegram offset polling. Public invalid-token requests established only error connectivity, not authorized delivery.

Maintain golden tests for production, length boundaries, masked/hidden characters, flood digest, resolved/expired requests, and literal rendering. Exercise all eligibility combinations and spoofed identity/delivery fields. Test duplicate clicks, digest changes, pairing expiry/reuse, nonce expiry, and reconnect behavior. A sentinel agent note and secret canaries must be absent from every outbound request, SSE frame, and log.

Electron pure-function tests can cover SSE parsing, platform options, and toast/OS selection. Actual notification buttons, focus behavior, signing, Windows activation, and Linux desktop differences require OS smoke tests. A live Slack/Telegram test uses an explicitly configured test account and is separate from ordinary CI.

## 8. Primary evidence

- [Electron 45 Notification API](https://github.com/electron/electron/blob/v45.0.0/docs/api/notification.md), [notifications tutorial](https://github.com/electron/electron/blob/v45.0.0/docs/tutorial/notifications.md), and [deep links](https://github.com/electron/electron/blob/v45.0.0/docs/tutorial/launch-app-from-url-in-another-app.md).
- [Slack Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode/), [Block Kit](https://docs.slack.dev/reference/block-kit/), [chat.postMessage](https://docs.slack.dev/reference/methods/chat.postMessage/), and [conversations.open](https://docs.slack.dev/reference/methods/conversations.open/).
- [Telegram Bot API](https://core.telegram.org/bots/api) and [bot deep linking](https://core.telegram.org/bots/features#deep-linking).
- [JDK WebSocket API](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/WebSocket.html).

## Appendix: recorded channel payload examples

These dated examples illustrate the reviewed message shapes; current formatters and policy checks determine actual eligibility.
```json
{"channel":"D0815ABCD","text":"Approval needed: sql_execute on dev-db","unfurl_links":false,"unfurl_media":false,"blocks":[
 {"type":"header","text":{"type":"plain_text","text":"Approval needed: sql_execute on dev-db"}},
 {"type":"section","fields":[{"type":"mrkdwn","text":"*Connection*\n`dev-db` · dev"},{"type":"mrkdwn","text":"*Endpoint*\n`dev`"},
   {"type":"mrkdwn","text":"*Client*\nCodex · `codex-laptop`"},{"type":"mrkdwn","text":"*Auto-deny*\n<!date^1792672452^{time_secs}|14:34:12 UTC+02:00>"}]},
 {"type":"rich_text","block_id":"act.ap_3kq7m2x9vt4p.1","elements":[{"type":"rich_text_preformatted","language":"sql","border":0,
   "elements":[{"type":"text","text":"UPDATE orders SET status = 'cancelled' WHERE id = 48213;"}]}]},
 {"type":"context","elements":[{"type":"mrkdwn","text":"Rule `dev.sql.dml-approval` · estimated rows 1"}]},
 {"type":"actions","block_id":"dec.ap_3kq7m2x9vt4p.1","elements":[
  {"type":"button","action_id":"gw_approve","style":"primary","text":{"type":"plain_text","text":"Approve"},"value":"v1|ins_7hc2qk4m|ap_3kq7m2x9vt4p|9f2c1a7e",
   "confirm":{"title":{"type":"plain_text","text":"Run this on dev-db?"},"text":{"type":"plain_text","text":"The statement above runs once, exactly as shown."},
              "confirm":{"type":"plain_text","text":"Approve and run"},"deny":{"type":"plain_text","text":"Cancel"},"style":"primary"}},
  {"type":"button","action_id":"gw_deny","style":"danger","text":{"type":"plain_text","text":"Deny"},"value":"v1|ins_7hc2qk4m|ap_3kq7m2x9vt4p|9f2c1a7e"},
  {"type":"button","action_id":"gw_open","text":{"type":"plain_text","text":"Open in app"},"value":"v1|ins_7hc2qk4m|ap_3kq7m2x9vt4p|9f2c1a7e"}]}]}
```

```json
{"chat_id":424242,"protect_content":true,"link_preview_options":{"is_disabled":true},
 "text":"Approval needed\nsql_execute on dev-db · dev · from Codex · endpoint dev\n\nUPDATE orders SET status = 'cancelled' WHERE id = 48213;\n\nAuto-deny at 14:34:12 · rule dev.sql.dml-approval",
 "entities":[{"type":"bold","offset":0,"length":15},{"type":"code","offset":16,"length":11},{"type":"code","offset":31,"length":6},
   {"type":"pre","offset":73,"length":56,"language":"sql"},{"type":"date_time","offset":144,"length":8,"unix_time":1792672452,"date_time_format":"T"}],
 "reply_markup":{"inline_keyboard":[[{"text":"Approve","style":"success","callback_data":"a:ap_3kq7m2x9vt4p:9f2c1a7e"},
   {"text":"Deny","style":"danger","callback_data":"d:ap_3kq7m2x9vt4p"}],[{"text":"Open in app","callback_data":"o:ap_3kq7m2x9vt4p"}]]}}
```

```json
{"type":"object","properties":{
 "desktop":{"type":"object","properties":{
   "enabled":{"type":"boolean","default":true},
   "soundForProd":{"type":"boolean","default":true,"description":"Play a sound for prod requests"},
   "maxBurst":{"type":"integer","default":3,"minimum":1,"maximum":10,"x-advanced":true,"description":"More than this in 10 s → one summary notification"}}},
 "slack":{"type":"object","properties":{
   "enabled":{"type":"boolean","default":false},
   "botToken":{"type":"string","title":"Bot User OAuth Token","writeOnly":true,"format":"password","pattern":"^xoxb-[A-Za-z0-9-]+$","x-gateway-secret":true},
   "appToken":{"type":"string","title":"App-level token (connections:write)","writeOnly":true,"format":"password","pattern":"^(xapp-|xoxe\\.xapp-)[A-Za-z0-9-]+$","x-gateway-secret":true},
   "deliverTo":{"enum":["dm","channel"],"default":"dm"},
   "channelId":{"type":"string","pattern":"^[CG][A-Z0-9]{8,}$","description":"Only when deliverTo=channel. Everyone in the channel sees the exact action."},
   "apiBase":{"type":"string","format":"uri","default":"https://slack.com/api/","x-advanced":true}}},
 "telegram":{"type":"object","properties":{
   "enabled":{"type":"boolean","default":false},
   "botToken":{"type":"string","writeOnly":true,"format":"password","pattern":"^[0-9]{5,}:[A-Za-z0-9_-]{30,}$","x-gateway-secret":true},
   "protectContent":{"type":"boolean","default":true},
   "confirmApprove":{"type":"boolean","default":true},
   "apiBase":{"type":"string","format":"uri","default":"https://api.telegram.org","x-advanced":true}}},
 "approvalRules":{"type":"object","properties":{
   "decisionsFrom":{"enum":["app","app-and-linked"],"default":"app-and-linked"},
   "requireCommentOnProd":{"type":"boolean","default":true}}}}}
```
