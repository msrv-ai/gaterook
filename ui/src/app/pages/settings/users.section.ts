import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GwIcon } from '../../kit/icons';

/** The server-mode preview's people (BLUEPRINT §9: names appear only in an explicitly labelled server-mode preview). */
const PEOPLE: readonly { readonly initials: string; readonly name: string; readonly role: string; readonly approves: boolean; readonly twoFactor: boolean }[] = [
  { initials: 'AM', name: 'Alex Morgan', role: 'Owner', approves: true, twoFactor: true },
  { initials: 'PN', name: 'Priya Nair', role: 'Admin · Approver', approves: true, twoFactor: true },
  { initials: 'SL', name: 'Sam Lee', role: 'Developer', approves: false, twoFactor: false },
  { initials: 'JW', name: 'Jonas Weber', role: 'Viewer', approves: false, twoFactor: false },
];

/**
 * Settings › Users & roles (design/pages/settings.html `#settings-users`): a read-only preview of server mode
 * (decision 1: the desktop has one local user who holds every permission; decisions and changes are signed "by you").
 * Invite people is unavailable with its reason; the people and roles are the kit's preview, labelled as such.
 */
@Component({
  selector: 'section[gwUsersSettings]',
  imports: [RouterLink, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout__section', id: 'settings-users', 'aria-labelledby': 'st-users-t' },
  template: `
    <header class="gw-section__header">
      <h2 class="gw-section__title" id="st-users-t">Users &amp; roles</h2>
      <p class="gw-section__description">Who can sign in to a shared gateway and what each role may do.</p>
      <div class="gw-section__actions">
        <a class="gw-btn gw-btn--ghost gw-btn--sm" routerLink="/activity" [queryParams]="{ view: 'changes' }"><svg lucideIcon="history" class="gw-icon gw-icon--sm"></svg> View setup changes</a>
        <button class="gw-btn gw-btn--sm" type="button" aria-disabled="true" aria-describedby="st-users-why"><svg lucideIcon="user-plus" class="gw-icon gw-icon--sm"></svg> Invite people</button>
      </div>
    </header>
    <hr class="gw-divider">
    <div class="gw-alert gw-alert--neutral gw-alert--compact" id="st-users-why">
      <svg lucideIcon="circle-help" class="gw-icon gw-alert__icon"></svg>
      <div class="gw-alert__content"><p class="gw-alert__title">Server mode only</p><p class="gw-alert__body">On this desktop you are the only user and hold every permission: decisions and changes are signed "by you", and Activity › Setup changes lists each one with You in By. In server mode people sign in with OIDC and get one of these roles; the people below are a preview.</p></div>
    </div>
    <div class="gw-form">
      <section class="gw-form__section" aria-labelledby="st-people-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-people-t">People <span class="gw-badge gw-badge--neutral gw-badge--sm">Server mode preview</span></h3>
          <p class="gw-form__description">In server mode every decision and every change is recorded with the person who made it. <a class="gw-link" routerLink="/activity" [queryParams]="{ view: 'changes' }">Setup changes in Activity</a></p>
        </header>
        <div class="gw-form__rows">
          <div class="gw-table-wrap" role="region" aria-labelledby="st-people-cap" tabindex="0">
            <table class="gw-table">
              <caption class="gw-sr-only" id="st-people-cap">People and roles in server mode, preview</caption>
              <thead>
                <tr>
                  <th scope="col">Person</th>
                  <th scope="col">Role</th>
                  <th scope="col">Approves</th>
                  <th scope="col">Two-factor</th>
                </tr>
              </thead>
              <tbody>
                @for (p of people; track p.name) {
                  <tr>
                    <th scope="row"><span class="gw-avatar gw-avatar--sm" aria-hidden="true">{{ p.initials }}</span> {{ p.name }}</th>
                    <td>{{ p.role }}</td>
                    <td>{{ p.approves ? 'Yes' : 'No' }}</td>
                    <td><span class="gw-status" [class.gw-status--success]="p.twoFactor" [class.gw-status--neutral]="!p.twoFactor"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">{{ p.twoFactor ? 'On' : 'Off' }}</span></span></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <section class="gw-form__section" aria-labelledby="st-roles-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-roles-t">Roles</h3>
          <p class="gw-form__description">One role per person; Approver can be added to any role. Owners and admins sign in with two-factor. Nobody, in any role, can read a stored secret.</p>
        </header>
        <div class="gw-form__rows">
          <dl class="gw-kv">
            <div class="gw-kv__row"><dt class="gw-kv__key">Owner</dt><dd class="gw-kv__value">Everything, including people, the master key and audit export. Decides on approval requests.</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Admin</dt><dd class="gw-kv__value">Connections, endpoints, policies and settings. Can't manage owners or the master key.</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Developer</dt><dd class="gw-kv__value">Uses endpoints and Playground and issues tokens for their own apps. Can't change policies or secrets.</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Approver</dt><dd class="gw-kv__value">Decides on approval requests, from the app, Slack or Telegram. Added on top of another role.</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Viewer</dt><dd class="gw-kv__value">Reads Activity and Insights. Can't change anything.</dd></div>
          </dl>
        </div>
      </section>
    </div>
  `,
})
export class GwUsersSettings {
  protected readonly people = PEOPLE;
}
