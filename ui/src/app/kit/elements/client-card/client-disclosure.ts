import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import { GwClientMark } from '../shared/client-mark';

/** The compact header shared by AI app cards; the entire row toggles the existing details. */
@Component({
  selector: 'header[gwClientDisclosure]',
  imports: [GwToggle, GwIcon, GwClientMark],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h3 class="gw-card__title" [id]="headingId()" tabindex="-1">
      <button class="gw-client-card__row" type="button" gwToggle [(expanded)]="expanded"
        [attr.aria-controls]="panelId()" [attr.aria-labelledby]="headingId() + '-name'" [attr.aria-describedby]="headingId() + '-status'">
        <span [gwClientMark]="client()"></span>
        <span class="gw-client-card__main"><span class="gw-text-ui" [id]="headingId() + '-name'">{{ name() }}</span></span>
        <span class="gw-status gw-status--{{ tone() }} gw-client-card__status" [id]="headingId() + '-status'">
          <span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">{{ status() }}</span>
        </span>
        <svg lucideIcon="chevron-down" class="gw-icon gw-icon--sm gw-btn__chevron"></svg>
      </button>
    </h3>
  `,
})
export class GwClientDisclosure {
  readonly client = input.required<string>();
  readonly name = input.required<string>();
  readonly tone = input.required<string>();
  readonly status = input.required<string>();
  readonly headingId = input.required<string>();
  readonly panelId = input.required<string>();
  readonly expanded = model(false);
}
