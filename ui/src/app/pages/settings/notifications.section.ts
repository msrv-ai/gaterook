import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ApprovalDetail } from '../../core/api/approvals.api';
import { ChannelKind, ChannelPatch } from '../../core/api/notifications.api';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwNotificationCard, GwNotificationPreview } from '../../kit/elements/approval-notification/approval-notification';
import { GwIcon } from '../../kit/icons';
import { GwReveal } from '../../kit/reveal';
import { GwTabs } from '../../kit/tabs';
import { ToastService } from '../../kit/toast.service';
import { SettingsData } from './settings.data';
import {
  channelOf,
  channelStatus,
  chatSetUp,
  configBool,
  configText,
  envRuleRows,
  onOff,
  policiesAfterSlack,
  previewRequest,
  ruleNoteOf,
  savedToast,
  slackChannelName,
  slackWorkspace,
  stillNotifiedBy,
  telegramBot,
  testSentToast,
} from './settings-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

type Preview = { readonly kind: 'none' } | { readonly kind: 'failed' } | { readonly kind: 'ready'; readonly request: ApprovalDetail };

/**
 * Settings › Notifications (design/pages/settings.html `#settings-notifications`): where approval requests reach you.
 * Every channel setting applies at once (`PUT /api/notifications/{desktop|slack|telegram}` with the changed fields
 * flat, as K2's `ChannelBody` takes them, and the channel's `version`). Telegram and Slack connect through a dialog
 * that takes the new tokens once: they leave the page in that one request, the core tests them before it saves
 * (Slack: `auth.test` and Socket Mode; Telegram: `getMe` and the webhook), and nobody can read them afterwards.
 * Disconnect Slack asks first (`--sm --danger`, Cancel has focus).
 *
 * The Telegram bot posts to the private chats of approvers who linked their account (K2): the core has no group chat
 * to set, so the dialog asks only for the token and the status names the bot (the kit's "Chat ID" field and
 * "Connected · chat …" — ARCHITECTURE.md §8.6.17). A bot with a webhook is connected only after the person agrees to
 * remove it (`confirm: "remove-webhook"`). Slack's "Post to a channel" needs a channel: picking it without one saved
 * shows the picker, and the choice is saved with the channel.
 *
 * Preview messages shows how each channel presents a request — a real one, read when the button is pressed (one
 * waiting now that won't expire while you look), else it says none is waiting. Send test notification asks the core
 * to post one to every channel that is on, and says what each did.
 *
 * Without K2 the core has no `/api/notifications` (404): the section keeps its layout, says the channels aren't
 * available in this build, and every control that would act is `aria-disabled` with that reason. The preview still
 * works: it needs only a request.
 */
@Component({
  selector: 'section[gwNotificationsSettings]',
  imports: [RouterLink, GwIcon, GwDialog, GwDialogClose, GwReveal, GwTabs, GwNotificationCard, GwNotificationPreview],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout__section', id: 'settings-notifications', 'aria-labelledby': 'st-notif-t' },
  templateUrl: './notifications.section.html',
})
export class GwNotificationsSettings {
  protected readonly data = inject(SettingsData);
  protected readonly time = inject(TimeService);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);

  private readonly previewDialog = viewChild.required<GwDialog>('previewDialog');
  private readonly slackDisconnectDialog = viewChild.required<GwDialog>('slackDisconnectDialog');
  private readonly telegramDialog = viewChild.required<GwDialog>('telegramDialog');
  private readonly slackDialog = viewChild.required<GwDialog>('slackDialog');
  private readonly botToken = viewChild<ElementRef<HTMLInputElement>>('botToken');
  private readonly slackBotToken = viewChild<ElementRef<HTMLInputElement>>('slackBotToken');
  private readonly slackAppToken = viewChild<ElementRef<HTMLInputElement>>('slackAppToken');

  /** The core has no notification channels (K2 not in this build). */
  protected readonly unavailable = computed(() => this.data.notifications().unavailable);
  /** The channels didn't load (an error other than "not in this build"). */
  protected readonly failed = computed(() => !!this.data.notifications().error && !this.unavailable());
  /** Nothing here may act: not in this build, or not loaded. */
  protected readonly off = computed(() => this.unavailable() || !this.data.notifications().value);
  protected readonly channels = computed(() => this.data.notifications().value?.channels ?? null);
  protected readonly desktop = computed(() => channelOf(this.channels(), 'desktop'));
  protected readonly slack = computed(() => channelOf(this.channels(), 'slack'));
  protected readonly telegram = computed(() => channelOf(this.channels(), 'telegram'));
  /** Off while the core can't send any; else as saved, on by default. */
  protected readonly desktopOn = computed(() => (this.off() ? false : (this.desktop()?.enabled ?? true)));
  protected readonly soundForProd = computed(() => (this.off() ? false : configBool(this.desktop(), 'soundForProd', true)));
  protected readonly slackUp = computed(() => chatSetUp(this.slack()));
  protected readonly telegramUp = computed(() => chatSetUp(this.telegram()));
  protected readonly slackStatus = computed(() => channelStatus(this.slack()));
  protected readonly telegramStatus = computed(() => channelStatus(this.telegram()));
  protected readonly workspace = computed(() => slackWorkspace(this.slack()));
  protected readonly bot = computed(() => telegramBot(this.telegram()));
  private readonly savedDeliverTo = computed(() => (configText(this.slack(), 'deliverTo') === 'channel' ? 'channel' : 'dm'));
  /** "Post to a channel" picked while no channel is saved: the picker shows, nothing is saved until a channel is. */
  protected readonly choosingChannel = signal(false);
  protected readonly deliverTo = computed(() => (this.choosingChannel() ? 'channel' : this.savedDeliverTo()));
  protected readonly slackChannel = computed(() => slackChannelName(this.slack()));
  protected readonly slackChannelId = computed(() => configText(this.slack(), 'channelId'));
  protected readonly slackChannels = computed(() => {
    const list = this.data.slackChannels();
    const id = this.slackChannelId();
    // The saved channel stays in the picker even when the list didn't load.
    return id && !list.some((c) => c.id === id) ? [{ id, name: this.slackChannel() ?? id }, ...list] : list;
  });
  protected readonly busy = signal<string | null>(null);

  // ── Preview messages ──────────────────────────────────────────────────────────────────────────────────────────

  protected readonly preview = signal<Preview>({ kind: 'none' });
  protected readonly previewBusy = signal(false);
  protected readonly ruleNote = computed(() => {
    const p = this.preview();
    return p.kind === 'ready' ? ruleNoteOf(p.request.impact) : null;
  });

  // ── Disconnect Slack ──────────────────────────────────────────────────────────────────────────────────────────

  protected readonly pendingCount = signal<number | null>(null);
  protected readonly stillNotified = computed(() => stillNotifiedBy(this.desktopOn(), this.telegramUp()));
  protected readonly policiesAfter = computed(() => policiesAfterSlack(envRuleRows(this.data.section('approval-rules')?.values?.['perEnvironment'])));
  protected readonly disconnectBusy = signal(false);
  protected readonly disconnectError = signal<string | null>(null);

  // ── Connect Telegram ──────────────────────────────────────────────────────────────────────────────────────────

  protected readonly tgTokenMissing = signal(false);
  protected readonly tgBusy = signal(false);
  protected readonly tgError = signal<string | null>(null);
  /** The core found a webhook on the bot: what it said, until the person agrees to remove it (the next submit does). */
  protected readonly tgWebhook = signal<string | null>(null);

  // ── Connect Slack ─────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly slackMissing = signal<{ readonly bot: boolean; readonly app: boolean }>({ bot: false, app: false });
  protected readonly slackBusy = signal(false);
  protected readonly slackError = signal<string | null>(null);

  // ── Channel settings apply at once ────────────────────────────────────────────────────────────────────────────

  protected async setDesktop(e: Event, key: 'enabled' | 'soundForProd'): Promise<void> {
    const input = e.target as HTMLInputElement;
    const on = input.checked;
    const label = key === 'enabled' ? 'Desktop notifications' : 'Play a sound for prod requests';
    const patch: ChannelPatch = key === 'enabled' ? { enabled: on } : { soundForProd: on };
    await this.apply('desktop', patch, label, onOff(on), () => (input.checked = !on));
  }

  protected async setDeliverTo(e: Event): Promise<void> {
    const input = e.target as HTMLInputElement;
    const value = input.value === 'channel' ? 'channel' : 'dm';
    if (value === 'channel' && !this.slackChannelId()) {
      // The core posts to a channel only once one is chosen: show the picker, save with the channel.
      this.choosingChannel.set(true);
      this.announcer.announce('Choose the channel to post requests to.');
      return;
    }
    this.choosingChannel.set(false);
    const text = value === 'channel' ? 'a channel' : 'direct messages to linked approvers';
    await this.apply('slack', { deliverTo: value }, 'Send requests to', text, () => {
      const back = input.closest('fieldset')?.querySelector<HTMLInputElement>(`input[value="${this.deliverTo()}"]`);
      if (back) back.checked = true;
    });
  }

  protected async setSlackChannel(e: Event): Promise<void> {
    const select = e.target as HTMLSelectElement;
    const ch = this.slackChannels().find((c) => c.id === select.value);
    if (!ch) return;
    const name = ch.name.startsWith('#') ? ch.name : `#${ch.name}`;
    await this.apply('slack', { deliverTo: 'channel', channelId: ch.id }, 'Channel', name, () => {
      select.value = this.slackChannelId() ?? '';
    });
    if (this.slackChannelId() === ch.id) this.choosingChannel.set(false);
  }

  private async apply(kind: ChannelKind, patch: ChannelPatch, label: string, value: string, undo: () => void): Promise<void> {
    if (this.busy()) {
      undo();
      return;
    }
    this.busy.set(kind);
    try {
      await this.data.updateChannel(kind, patch);
      this.toasts.show(savedToast(label, value));
    } catch (err) {
      undo();
      this.toasts.show({ tone: 'danger', title: `${label} wasn’t saved`, body: this.errorText(err) });
    } finally {
      this.busy.set(null);
    }
  }

  protected async sendTest(): Promise<void> {
    if (this.off() || this.busy()) return;
    this.busy.set('test');
    try {
      const sent = await this.data.testNotifications();
      this.toasts.show(testSentToast(sent, this.channels() ?? []));
    } catch (err) {
      this.toasts.show({ tone: 'danger', title: 'The test notification wasn’t sent', body: this.errorText(err) });
    } finally {
      this.busy.set(null);
    }
  }

  // ── Preview messages ──────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Reads the request first, then opens the dialog with it rendered, so the first tab's `autofocus` takes the focus
   * (kit). Without a request the dialog says so and Close has the focus.
   */
  protected async openPreview(e: Event): Promise<void> {
    if (this.previewBusy()) return;
    const opener = e.currentTarget as HTMLElement;
    this.previewBusy.set(true);
    try {
      const item = previewRequest(await this.data.pending(), this.time.now());
      this.preview.set(item ? { kind: 'ready', request: await this.data.approval(item.id) } : { kind: 'none' });
    } catch {
      this.preview.set({ kind: 'failed' });
    } finally {
      this.previewBusy.set(false);
    }
    this.previewDialog().openAfterRender(opener);
  }

  // ── Disconnect Slack ──────────────────────────────────────────────────────────────────────────────────────────

  protected async askDisconnect(e: Event): Promise<void> {
    this.disconnectError.set(null);
    this.disconnectBusy.set(false);
    this.pendingCount.set(null);
    this.slackDisconnectDialog().open(e.currentTarget as HTMLElement);
    try {
      this.pendingCount.set((await this.data.pending()).length);
    } catch {
      // The row just isn't shown.
    }
  }

  protected async disconnectSlack(): Promise<void> {
    if (this.disconnectBusy()) return;
    this.disconnectBusy.set(true);
    try {
      await this.data.disconnectSlack();
      this.choosingChannel.set(false);
      this.slackDisconnectDialog().close('disconnect');
      this.toasts.show({ tone: 'success', title: 'Slack disconnected', body: `Approval requests now reach you ${this.reachText()}.` });
    } catch (err) {
      this.disconnectError.set(this.errorText(err));
    } finally {
      this.disconnectBusy.set(false);
    }
  }

  private reachText(): string {
    const d = this.desktopOn();
    const tg = this.telegramUp();
    if (d && tg) return 'on the desktop and in Telegram';
    if (d) return 'on the desktop only';
    if (tg) return 'in Telegram only';
    return 'in Approvals only';
  }

  // ── Connect Telegram ──────────────────────────────────────────────────────────────────────────────────────────

  protected openTelegram(e: Event): void {
    this.tgTokenMissing.set(false);
    this.tgError.set(null);
    this.tgWebhook.set(null);
    this.telegramDialog().open(e.currentTarget as HTMLElement);
  }

  protected async connectTelegram(e: Event): Promise<void> {
    e.preventDefault();
    if (this.tgBusy()) return;
    const token = this.botToken()?.nativeElement;
    const missing = !token?.value;
    this.tgTokenMissing.set(missing);
    if (!token || missing) {
      token?.focus();
      return;
    }
    const removeWebhook = this.tgWebhook() !== null;
    this.tgBusy.set(true);
    this.tgError.set(null);
    try {
      // The token leaves the page in this one request: the core checks it with Telegram, then stores it encrypted.
      await this.data.updateChannel('telegram', { enabled: true, botToken: token.value, ...(removeWebhook ? { confirm: 'remove-webhook' as const } : {}) });
      this.clearTokens();
      this.telegramDialog().close('connected');
      const bot = this.bot();
      this.toasts.show({
        tone: 'success',
        title: 'Telegram connected',
        body: `${bot ? `The bot ${bot} checks out.` : 'The bot checks out.'} It sends requests to approvers who link their Telegram account.`,
      });
    } catch (err) {
      if (isApiError(err) && err.confirm === 'remove-webhook' && !removeWebhook) {
        // The bot gets its updates through a webhook: the core removes it only with the person's yes.
        this.tgWebhook.set(err.detail);
      } else {
        this.tgError.set(this.errorText(err));
      }
    } finally {
      this.tgBusy.set(false);
    }
  }

  // ── Connect Slack ─────────────────────────────────────────────────────────────────────────────────────────────

  protected openSlack(e: Event): void {
    this.slackMissing.set({ bot: false, app: false });
    this.slackError.set(null);
    this.slackDialog().open(e.currentTarget as HTMLElement);
  }

  protected async connectSlack(e: Event): Promise<void> {
    e.preventDefault();
    if (this.slackBusy()) return;
    const bot = this.slackBotToken()?.nativeElement;
    const app = this.slackAppToken()?.nativeElement;
    if (!bot || !app) return;
    const missing = { bot: !bot.value, app: !app.value };
    this.slackMissing.set(missing);
    if (missing.bot || missing.app) {
      (missing.bot ? bot : app).focus();
      return;
    }
    this.slackBusy.set(true);
    this.slackError.set(null);
    // A channel is kept only while one is saved; otherwise direct messages (the kit's recommendation).
    const deliverTo = this.savedDeliverTo() === 'channel' && this.slackChannelId() ? 'channel' : 'dm';
    try {
      await this.data.updateChannel('slack', { enabled: true, deliverTo, botToken: bot.value, appToken: app.value });
      this.clearTokens();
      this.slackDialog().close('connected');
      this.toasts.show({
        tone: 'success',
        title: 'Slack connected',
        body:
          deliverTo === 'channel' && this.slackChannel()
            ? `Requests are posted to ${this.slackChannel()}. Linked approvers decide with the buttons on the message.`
            : 'Requests go to linked approvers as direct messages.',
      });
    } catch (err) {
      this.slackError.set(this.errorText(err));
    } finally {
      this.slackBusy.set(false);
    }
  }

  /** Whatever was typed as a token leaves the page with the dialog, hidden again. */
  protected clearTokens(): void {
    for (const f of [this.botToken(), this.slackBotToken(), this.slackAppToken()]) {
      if (!f) continue;
      f.nativeElement.value = '';
      f.nativeElement.type = 'password';
    }
    this.tgWebhook.set(null);
  }

  /** The core's words; a stale `version` (someone saved meanwhile) gets the page's own sentence. */
  private errorText(e: unknown): string {
    if (isApiError(e) && e.status === 409 && e.title === 'Changed meanwhile') return 'The channel changed meanwhile, in another window. Check it, then try again.';
    return isApiError(e) ? e.detail : NO_ANSWER;
  }
}
