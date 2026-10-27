import { ChangeDetectionStrategy, Component, Injector, computed, inject, linkedSignal, signal } from '@angular/core';
import { FormField, form } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { Announcer } from '../../kit/announcer';
import { GwSaveBar } from '../../kit/elements/settings-layout/settings-layout';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { SettingsData } from './settings.data';
import { DecisionsFrom, announceParts, boolOf, channelOf, envRuleRows, formSavedToast, slackChannelName, strOf } from './settings-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

interface RulesModel {
  decisionsFrom: DecisionsFrom;
  requireCommentOnProd: boolean;
}

/**
 * Settings › Approval rules (design/pages/settings.html `#settings-approval-rules`): where decisions can be made and
 * whether a prod approval needs a comment — one Save form (`PUT /api/settings/approval-rules`; the comment rule lives
 * in the prod policy, the core writes it there). The per-environment table is read-only: timeouts, comment and the
 * channels each policy announces in come from the policies (`perEnvironment`), each row links to its Approvals
 * section in Policies. The prod comment cell follows the checkbox while it is unsaved.
 */
@Component({
  selector: 'section[gwApprovalRulesSettings]',
  imports: [RouterLink, FormField, GwIcon, GwSaveBar],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout__section', id: 'settings-approval-rules', 'aria-labelledby': 'st-rules-t' },
  template: `
    <header class="gw-section__header">
      <h2 class="gw-section__title" id="st-rules-t">Approval rules</h2>
      <p class="gw-section__description">Where decisions can be made and what a prod decision needs. Timeouts and limits are set per environment in Policies.</p>
      <div class="gw-section__actions">
        <a class="gw-btn gw-btn--ghost gw-btn--sm" routerLink="/activity" [queryParams]="{ q: 'decision:expired' }"><svg lucideIcon="activity" class="gw-icon gw-icon--sm"></svg> View expired requests</a>
      </div>
    </header>
    <hr class="gw-divider">
    <div class="gw-alert gw-alert--info">
      <svg lucideIcon="info" class="gw-icon gw-alert__icon"></svg>
      <div class="gw-alert__content">
        <p class="gw-alert__title">Every mutation waits for a person</p>
        <p class="gw-alert__body">This has no switch: no endpoint, environment or policy can skip it. The rules below only decide where a request is answered, how long it waits and what the answer needs. On this desktop you answer every request.</p>
      </div>
    </div>
    <div class="gw-form gw-form--sticky-actions">
      <form class="gw-form__section" id="st-rules" method="dialog" novalidate aria-labelledby="st-decisions-t" (submit)="save($event)" (reset)="discard($event)">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-decisions-t">Decisions</h3>
          <p class="gw-form__description">Applies to every endpoint and environment.</p>
        </header>
        <div class="gw-form__rows">
          <fieldset class="gw-field">
            <legend class="gw-field__label">Where decisions can be made</legend>
            <div class="gw-radio">
              <input class="gw-radio__input" type="radio" id="st-approvers-app" value="app" [formField]="rulesForm.decisionsFrom" aria-describedby="st-approvers-app-hint">
              <span class="gw-radio__control" aria-hidden="true"></span>
              <label class="gw-radio__label" for="st-approvers-app">Only in this app</label>
              <p class="gw-radio__hint" id="st-approvers-app-hint">Slack and Telegram messages show Open in app instead of Approve and Deny. Desktop notifications keep Deny.</p>
            </div>
            <div class="gw-radio">
              <input class="gw-radio__input" type="radio" id="st-approvers-linked" value="app-and-linked" [formField]="rulesForm.decisionsFrom" aria-describedby="st-approvers-linked-hint">
              <span class="gw-radio__control" aria-hidden="true"></span>
              <label class="gw-radio__label" for="st-approvers-linked">In this app and from linked Slack or Telegram accounts</label>
              <p class="gw-radio__hint" id="st-approvers-linked-hint">Approve is still dropped from every prod request, from a message that can't show the whole action, and when a comment is required. In server mode only people allowed by the policy can link an account.</p>
            </div>
          </fieldset>
          <div class="gw-checkbox">
            <input class="gw-checkbox__input" type="checkbox" id="st-comment-prod" [formField]="rulesForm.requireCommentOnProd" aria-describedby="st-comment-prod-hint">
            <span class="gw-checkbox__control" aria-hidden="true"></span>
            <label class="gw-checkbox__label" for="st-comment-prod">Require a comment on prod</label>
            <p class="gw-checkbox__hint" id="st-comment-prod-hint">Approve and run stays unavailable until you write why. Slack and Telegram offer only Deny and Open in app for prod either way; desktop notifications always offer Review and Deny.</p>
          </div>
          @if (error(); as err) {
            <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
              <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__title">Approval rules weren't saved</p><p class="gw-alert__body">{{ err }}</p></div>
            </div>
          }
        </div>
      </form>

      <section class="gw-form__section" aria-labelledby="st-per-env-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-per-env-t">Per environment</h3>
          <p class="gw-form__description">Timeouts are set in each environment's policy; the prod comment follows the rule above. No answer in time counts as a denial: the request is marked Expired and the agent is told.</p>
        </header>
        <div class="gw-form__rows">
          <div class="gw-table-wrap" role="region" aria-labelledby="st-per-env-cap" tabindex="0">
            <table class="gw-table">
              <caption class="gw-sr-only" id="st-per-env-cap">Approval rules per environment, set in Policies</caption>
              <thead>
                <tr>
                  <th scope="col">Environment</th>
                  <th scope="col">Timeout</th>
                  <th scope="col">Who can approve</th>
                  <th scope="col">Comment</th>
                  <th scope="col"><span class="gw-sr-only">Edit</span></th>
                </tr>
              </thead>
              <tbody>
                @for (r of rows(); track r.env) {
                  <tr>
                    <th scope="row"><span class="gw-env" [class]="'gw-env--' + r.env">{{ r.env }}</span></th>
                    <td class="gw-tabular">{{ r.timeout }}</td>
                    <td>You (local user)</td>
                    <td [attr.id]="r.env === 'prod' ? 'st-prod-comment' : null">{{ r.comment }}</td>
                    <td><a class="gw-link" [routerLink]="['/policies', r.env]" [fragment]="r.env + '-approvals'">Edit in Policies<span class="gw-sr-only">: {{ r.env }} approval rules</span></a></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="gw-text-small gw-fg-secondary">@for (p of announce(); track $index) {@if (p.mono) {<span class="gw-mono">{{ p.text }}</span>} @else {{{ p.text }}}}</p>
        </div>
      </section>

      <div gwSaveBar formId="st-rules" saveLabel="Save approval rules" what="Approval rules" [dirty]="dirty()" [busy]="busy()"></div>
    </div>
  `,
})
export class GwApprovalRulesSettings {
  private readonly data = inject(SettingsData);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);

  private readonly values = computed(() => this.data.section('approval-rules')?.values);
  private readonly saved = computed<RulesModel>(() => ({
    decisionsFrom: strOf(this.values(), 'decisionsFrom', 'app-and-linked') === 'app' ? 'app' : 'app-and-linked',
    requireCommentOnProd: boolOf(this.values(), 'requireCommentOnProd', true),
  }));

  /** Follows the saved values until the person changes something (a reload or SSE never overwrites an edit). */
  protected readonly model = linkedSignal<RulesModel, RulesModel>({
    source: this.saved,
    computation: (saved, previous) => (previous && !same(previous.value, previous.source) ? previous.value : saved),
  });
  protected readonly rulesForm = form(this.model, () => undefined, { injector: this.injector });
  protected readonly dirty = computed(() => !same(this.model(), this.saved()));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** The table, with the prod comment following the unsaved checkbox. */
  protected readonly rows = computed(() => {
    const require = this.model().requireCommentOnProd;
    const savedRequire = this.saved().requireCommentOnProd;
    return envRuleRows(this.values()?.['perEnvironment']).map((r) =>
      r.env !== 'prod' || require === savedRequire ? r : { ...r, comment: require ? 'Required' : 'Optional' },
    );
  });
  protected readonly announce = computed(() => {
    const slack = channelOf(this.data.notifications().value?.channels, 'slack');
    return announceParts(this.rows(), slackChannelName(slack));
  });

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy() || !this.dirty()) return;
    this.busy.set(true);
    this.error.set(null);
    const m = this.model();
    try {
      await this.data.save('approval-rules', { decisionsFrom: m.decisionsFrom, requireCommentOnProd: m.requireCommentOnProd });
      // The kit names the form by its legend: "Decisions saved".
      this.toasts.show(formSavedToast('Decisions'));
    } catch (err) {
      this.error.set(
        isApiError(err) && err.status === 409 ? 'These rules changed meanwhile, in another window or in the prod policy. Check them, then save again.' : isApiError(err) ? err.detail : NO_ANSWER,
      );
      this.announcer.announce('Approval rules weren’t saved');
    } finally {
      this.busy.set(false);
    }
  }

  protected discard(e: Event): void {
    e.preventDefault();
    this.model.set(this.saved());
    this.error.set(null);
  }
}

function same(a: RulesModel, b: RulesModel): boolean {
  return a.decisionsFrom === b.decisionsFrom && a.requireCommentOnProd === b.requireCommentOnProd;
}
