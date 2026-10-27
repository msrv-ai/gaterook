import { Injectable, inject } from '@angular/core';
import { TestOutcome } from './connections.api';
import { ApiClient } from './api-client';
import { Iso, JsonObject, WithReason } from './types';

export type ChannelKind = 'desktop' | 'slack' | 'telegram';

/**
 * One notification channel (step K2, `notifications.NotificationViews.Channel`): its config never carries a token
 * value. `config` — desktop `{soundForProd, maxBurst}`; Slack `{deliverTo, channelId, channelName, workspace{team,
 * teamId, bot, botUserId, appId}}`; Telegram `{protectContent, confirmApprove, bot{id, username}}` (the bot posts to
 * the private chats of linked approvers: there is no chat id to set).
 */
export interface NotificationChannel {
  readonly kind: ChannelKind;
  readonly enabled: boolean;
  readonly config: JsonObject;
  readonly state: string | null;
  readonly stateDetail: string | null;
  readonly lastDeliveryAt: Iso | null;
  readonly version: number;
  /** False: changed outside MCP Gateway, off until saved again. */
  readonly verified?: boolean;
}

/**
 * `PUT /api/notifications/{channel}` (`admin.notifications.NotificationsController.ChannelBody`): flat fields of that
 * channel; a missing one keeps its value, a token is replaced only when sent. Tokens leave the page in this request
 * only; the core tests them before it saves (422 with the test when they don't work, `confirm: "remove-webhook"`
 * when a Telegram bot has a webhook the person must agree to remove).
 */
export interface ChannelPatch {
  readonly enabled?: boolean;
  readonly soundForProd?: boolean;
  readonly maxBurst?: number;
  readonly deliverTo?: 'dm' | 'channel';
  readonly channelId?: string;
  readonly botToken?: string;
  readonly appToken?: string;
  readonly protectContent?: boolean;
  readonly confirmApprove?: boolean;
  readonly confirm?: 'remove-webhook';
}

/** `POST /api/notifications/test`: what each channel did with the test message. */
export interface NotificationsSent {
  readonly channels: readonly { readonly channel: ChannelKind; readonly state: 'sent' | 'failed' | 'off'; readonly detail: string | null }[];
}

export interface NotifyLink {
  readonly id: string;
  readonly channel: ChannelKind;
  readonly display: string | null;
  readonly linkedAt: Iso | null;
  readonly state: string;
}

export interface Notifications {
  readonly channels: NotificationChannel[];
  readonly links: NotifyLink[];
}

@Injectable({ providedIn: 'root' })
export class NotificationsApi {
  private readonly api = inject(ApiClient);

  get(): Promise<Notifications> {
    return this.api.get('/api/notifications');
  }

  /** Tokens are checked before saving: 422 with a TestResult when they don't work. */
  update(kind: ChannelKind, body: ChannelPatch & { readonly version: number } & WithReason): Promise<NotificationChannel> {
    return this.api.put(ApiClient.path`/api/notifications/${kind}`, body);
  }

  /** The stored tokens of Slack or Telegram, checked again (the connection form's TestOutcome). */
  test(kind: 'slack' | 'telegram'): Promise<TestOutcome> {
    return this.api.post(ApiClient.path`/api/notifications/${kind}/test`);
  }

  /** "Send test notification": every channel that is on gets one. */
  sendTest(): Promise<NotificationsSent> {
    return this.api.post('/api/notifications/test');
  }

  slackChannels(): Promise<{ readonly id: string; readonly name: string }[]> {
    return this.api.get('/api/notifications/slack/channels');
  }

  linkSlack(email: string): Promise<NotifyLink> {
    return this.api.post('/api/notifications/slack/links', { email });
  }

  telegramPairing(): Promise<{ readonly id: string; readonly code: string; readonly link: string; readonly expiresAt: Iso }> {
    return this.api.post('/api/notifications/telegram/pairing');
  }

  confirmTelegramPairing(id: string): Promise<NotifyLink> {
    return this.api.post(ApiClient.path`/api/notifications/telegram/pairing/${id}/confirm`);
  }

  deleteLink(id: string): Promise<void> {
    return this.api.delete(ApiClient.path`/api/notifications/links/${id}`);
  }

  disconnectSlack(body: WithReason = {}): Promise<void> {
    return this.api.delete('/api/notifications/slack', body);
  }
}
