import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { GwNotificationCard, GwNotificationPreview } from '../../kit/elements/approval-notification/approval-notification';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_REQUESTS } from '../fixtures';

/** Dev only: design/elements/approval-notification.html. */
@Component({
  selector: 'gw-approval-notification-demo',
  imports: [GwDemoHeader, GwNotificationCard, GwNotificationPreview, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="s-desktop">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-desktop">Desktop notification</h2>
        <p class="gw-section__description">Shown by the OS while the window is in the background. Review and Deny only: nothing is approved blind from a notification.</p>
      </header>
      <section gwNotificationCard heading="Desktop notification" icon="monitor">
        <span gwCardDescription>macOS Notification Center and the Windows notification area, sent for <code class="gw-code">sql_execute</code> on <code class="gw-code">dev-db</code>.</span>
        <div class="gw-grid gw-grid--2">
          <figure gwNotificationPreview="desktop" os="mac" caption="macOS" [request]="req.sql"></figure>
          <figure gwNotificationPreview="desktop" os="win" caption="Windows" [request]="req.sql"></figure>
        </div>
        <div class="gw-alert gw-alert--info gw-alert--compact">
          <svg lucideIcon="info" class="gw-icon gw-alert__icon"></svg>
          <div class="gw-alert__content">
            <p class="gw-alert__title">Review opens the request in the app</p>
            <p class="gw-alert__body">There is no Approve button in the notification: approving needs the exact action on screen. Deny works from here.</p>
          </div>
        </div>
      </section>
    </section>

    <section class="gw-section" aria-labelledby="s-slack">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-slack">Slack message</h2>
        <p class="gw-section__description">Block Kit layout from kit parts: header, fields, the full statement, context and buttons. A direct message to each linked approver is the recommended default: the exact action can hold customer data, and everyone in a channel would see it. This workspace (<span class="gw-mono">acme</span>) posts to the channel <span class="gw-mono">#deploy-approvals</span>, chosen in Settings › Notifications. The agent's note is never sent.</p>
      </header>
      <section gwNotificationCard heading="Slack" letter="S">
        <span gwCardDescription>Posted by the GateRook app to <span class="gw-mono">#deploy-approvals</span> in the <span class="gw-mono">acme</span> workspace. The buttons act only for you, the linked approver.</span>
        <figure gwNotificationPreview="slack" slackChannel="#deploy-approvals" ruleNote="estimated rows 1" [request]="req.sql"></figure>
        <div class="gw-alert gw-alert--info gw-alert--compact">
          <svg lucideIcon="info" class="gw-icon gw-alert__icon"></svg>
          <div class="gw-alert__content">
            <p class="gw-alert__title">Direct messages to linked approvers are recommended</p>
            <p class="gw-alert__body">Approve and Deny work only for approvers who linked their Slack account in Settings › Notifications. Posting to a channel such as <span class="gw-mono">#deploy-approvals</span> is a choice there, with the hint “Everyone in the channel sees the exact action”; in a channel, anyone who isn't a linked approver gets a private reply with Open in app. A statement over 3,000 characters is not cut: Approve is dropped and Open in app remains, and so it is when the policy requires a comment (prod).</p>
          </div>
        </div>
      </section>
    </section>

    <section class="gw-section" aria-labelledby="s-telegram">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-telegram">Telegram message</h2>
        <p class="gw-section__description">A bot message with the statement in a code block and an inline keyboard under it. Telegram is not set up in this workspace (Settings › Notifications); this is what each linked approver gets once a bot is added.</p>
      </header>
      <section gwNotificationCard heading="Telegram" letter="T">
        <span gwCardDescription>Sent by the GateRook bot to each linked approver.</span>
        <figure gwNotificationPreview="telegram" [request]="req.sql"></figure>
        <div class="gw-alert gw-alert--info gw-alert--compact">
          <svg lucideIcon="info" class="gw-icon gw-alert__icon"></svg>
          <div class="gw-alert__content">
            <p class="gw-alert__title">Only linked approvers get this message</p>
            <p class="gw-alert__body">Each approver starts the bot and confirms the code from Settings · Notifications. Over 4,096 characters the statement is not cut: Approve is dropped and Open in app remains.</p>
          </div>
        </div>
      </section>
    </section>

    <section class="gw-section" aria-labelledby="s-prod">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-prod">A production request, in every channel</h2>
        <p class="gw-section__description">Prod never hides in a caption or a small chip: every channel titles it “PROD · Approval needed” and the first line says it runs on production. The prod policy requires a comment to approve (the default), so Slack and Telegram drop Approve and keep Deny and Open in app; the desktop notification is always Review and Deny. Same request: runbook <code class="gw-code">restart-api</code> on <code class="gw-code">prod-vm</code> through <code class="gw-code">incident-prod</code>, 10 min before auto-deny.</p>
      </header>
      <div class="gw-grid gw-grid--auto">
        <section gwNotificationCard heading="Desktop, prod" icon="monitor">
          <span gwCardDescription>macOS; Windows uses the same text.</span>
          <figure gwNotificationPreview="desktop" [request]="req.prod"></figure>
        </section>
        <section gwNotificationCard heading="Slack, prod, comment required" letter="S">
          <span gwCardDescription>In <span class="gw-mono">#deploy-approvals</span>. A comment can't be given from Slack, so Approve leaves the message.</span>
          <figure gwNotificationPreview="slack" slackChannel="#deploy-approvals" [request]="req.prod"></figure>
        </section>
        <section gwNotificationCard heading="Telegram, prod, comment required" letter="T">
          <span gwCardDescription>If the prod policy requires a comment, Approve leaves the channel.</span>
          <figure gwNotificationPreview="telegram" [request]="req.prod"></figure>
        </section>
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-toast">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-toast">In-app toast</h2>
        <p class="gw-section__description">While the window is in front, a toast with Review replaces the OS notification. It stays until it is used or dismissed.</p>
      </header>
      <section gwNotificationCard heading="In-app toast" icon="bell">
        <span gwCardDescription>Bottom-right of the window; the titlebar bell keeps the count.</span>
        <div class="gw-card__actions" gwCardActions>
          <button class="gw-btn gw-btn--sm" type="button" (click)="live()"><svg lucideIcon="bell-ring" class="gw-icon gw-icon--sm"></svg>Show live toast</button>
        </div>
        <figure gwNotificationPreview="toast" [request]="req.sql"></figure>
        <div class="gw-alert gw-alert--info gw-alert--compact">
          <svg lucideIcon="info" class="gw-icon gw-alert__icon"></svg>
          <div class="gw-alert__content">
            <p class="gw-alert__title">The toast never holds the decision</p>
            <p class="gw-alert__body">Review opens the request in Approvals. Dismissing the toast leaves the request waiting there.</p>
          </div>
        </div>
      </section>
    </section>

    <section class="gw-section" aria-labelledby="s-after">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-after">After a decision or a timeout</h2>
        <p class="gw-section__description">Channel messages are edited in place: the buttons give way to the outcome, so nobody acts on a request that is already closed.</p>
      </header>
      <div class="gw-grid gw-grid--2">
        <section gwNotificationCard heading="Slack, approved in the app" letter="S">
          <span gwCardDescription>An earlier request's message, edited at 12:21:18 when you approved it.</span>
          <figure gwNotificationPreview="slack" [request]="req.approved"></figure>
        </section>
        <section gwNotificationCard heading="Telegram, expired" letter="T">
          <span gwCardDescription>No decision in 10 min (the stage window); the keyboard is removed.</span>
          <figure gwNotificationPreview="telegram" [request]="req.expired"></figure>
        </section>
      </div>
    </section>
  `,
})
export class ApprovalNotificationDemo {
  private readonly toasts = inject(ToastService);
  protected readonly el = kitElement('approval-notification');
  protected readonly req = DEMO_REQUESTS;

  protected live(): void {
    this.toasts.show({
      title: 'Approval needed',
      body: 'sql_execute on dev-db (dev) from Codex. Auto-deny at 14:34:12.',
      tone: 'warning',
      actions: [{ label: 'Review', onClick: () => undefined }],
    });
  }
}
