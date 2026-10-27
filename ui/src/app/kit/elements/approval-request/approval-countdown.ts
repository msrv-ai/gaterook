import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { GwCountdown } from '../../countdown';
import { GwIcon } from '../../icons';

/**
 * `<div gwApprovalCountdown [expiresAt] [total] (ended)>`: the request's time to auto-deny (kit `__countdown`, kit.js
 * "countdown"): "Auto-deny in 4:12" over a thin bar that empties as the window runs out. Warning tone until the last
 * minute, then danger (the colour moves from the icon to the whole line); at zero "Expired, denied automatically"
 * without the bar, and `ended` fires once. The line is a `role="timer"` (not live: no announcement every second).
 */
@Component({
  selector: 'div[gwApprovalCountdown]',
  imports: [GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  hostDirectives: [
    {
      directive: GwCountdown,
      inputs: ['gwCountdown: expiresAt', 'gwCountdownTotal: total', 'gwCountdownAnnounce: announce'],
      outputs: ['ended'],
    },
  ],
  host: { class: 'gw-approval-request__countdown' },
  template: `
    @if (!cd.expired()) {
      <p class="gw-approval-request__countdown-text gw-text-small gw-tabular" [class.gw-fg-danger]="cd.urgent()" role="timer"><svg lucideIcon="timer" class="gw-icon gw-icon--sm" [class.gw-fg-warning]="!cd.urgent()"></svg>Auto-deny in <span>{{ cd.text() }}</span></p>
      <div class="gw-progress gw-progress--sm" [class.gw-progress--warning]="!cd.urgent()" [class.gw-progress--danger]="cd.urgent()" aria-hidden="true"><div class="gw-progress__bar"></div></div>
    } @else {
      <p class="gw-approval-request__countdown-text gw-text-small gw-tabular"><svg lucideIcon="timer-off" class="gw-icon gw-icon--sm"></svg>Expired, denied automatically</p>
    }
  `,
})
export class GwApprovalCountdown {
  protected readonly cd = inject(GwCountdown);
}
