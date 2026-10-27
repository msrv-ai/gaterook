import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ConnectionRow } from '../../../core/api/connections.api';
import { CatalogEntry } from '../../../core/api/plugins.api';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwIcon } from '../../../kit/icons';
import { ToastService } from '../../../kit/toast.service';
import { joinAnd } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureText } from './failure';

let nextId = 0;

/**
 * `<dialog gwTemplateInstallDialog>`: installs a catalog template (kit Catalog › "PostgreSQL slow queries" › Install).
 * A template runs on one connection ("Runs on", `POST /api/templates {catalogRef, connection}`), so the one-click
 * Install of the kit asks for it first; its tools start Off until they are added to an endpoint. Focus starts on the
 * connection select (the form's first field).
 */
@Component({
  selector: 'dialog[gwTemplateInstallDialog]',
  imports: [GwIcon, GwDialogClose],
  hostDirectives: [{ directive: GwDialog, inputs: ['gwDialogStatic'], outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-dialog gw-dialog--sm',
    '[attr.aria-labelledby]': 'entry() ? uid + "-t" : null',
    '[attr.aria-describedby]': 'entry() ? uid + "-d" : null',
  },
  template: `
    @if (entry(); as e) {
      <form method="dialog" (submit)="install($event)">
        <header class="gw-dialog__header">
          <span class="gw-icon-tile"><svg lucideIcon="file-code" class="gw-icon"></svg></span>
          <h2 class="gw-dialog__title" [id]="uid + '-t'">Install template {{ e.name }}</h2>
          <p class="gw-dialog__description" [id]="uid + '-d'">Pick the connection it runs on. Its tools start Off until you add them to an endpoint.</p>
          <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
        </header>
        <div class="gw-dialog__body">
          <div class="gw-stack">
            <div class="gw-field">
              <label class="gw-field__label" [for]="uid + '-conn'">Runs on</label>
              <select class="gw-select" [id]="uid + '-conn'" autofocus required [attr.aria-describedby]="uid + '-conn-hint'" (change)="connection.set($any($event.target).value)">
                @for (c of options(); track c.name) {
                  <option [value]="c.name" [selected]="connection() === c.name">{{ c.name }} · {{ c.typeName }} · {{ c.env }}</option>
                }
              </select>
              <p class="gw-field__hint" [id]="uid + '-conn-hint'">@if (options().length) {The secret stays with the connection.} @else {No connection can run this template yet. Add one first.}</p>
            </div>
            @if (error()) {
              <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
                <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
                <div class="gw-alert__content"><p class="gw-alert__title">{{ e.name }} wasn't installed</p><p class="gw-alert__body">{{ error() }}</p></div>
              </div>
            }
          </div>
        </div>
        <footer class="gw-dialog__footer">
          <button class="gw-btn" type="button" gwDialogClose>Cancel</button>
          <button class="gw-btn gw-btn--primary" type="submit" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() || !connection() ? 'true' : null" [attr.aria-describedby]="connection() ? null : uid + '-conn-hint'">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}Install template</button>
        </footer>
      </form>
    }
  `,
})
export class GwTemplateInstallDialog {
  private readonly data = inject(ConnectorsData);
  private readonly dialog = inject(GwDialog);
  private readonly toasts = inject(ToastService);

  protected readonly uid = `ti-${++nextId}`;
  protected readonly entry = signal<CatalogEntry | null>(null);
  protected readonly connection = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** The connections the template can run on (its connection types; any when the catalog doesn't say). */
  protected readonly options = computed<readonly ConnectionRow[]>(() => {
    const e = this.entry();
    const types = e?.connectionTypes ?? null;
    return (this.data.connections.value() ?? []).filter((c) => c.type !== 'mcp-server' && (!types?.length || types.includes(c.type)));
  });

  show(entry: CatalogEntry, opener: HTMLElement | null): void {
    this.entry.set(entry);
    this.error.set(null);
    this.busy.set(false);
    this.connection.set(this.options()[0]?.name ?? null);
    this.dialog.openAfterRender(opener);
  }

  protected async install(ev: Event): Promise<void> {
    ev.preventDefault();
    const e = this.entry();
    const connection = this.connection();
    if (!e || !connection || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const saved = await this.data.installCatalogTemplate(`${e.id}@${e.version}`, connection);
      if (!saved.template) {
        this.error.set(saved.compile?.errors.join(' ') || 'The core didn’t compile it against this connection.');
        return;
      }
      this.dialog.close('installed');
      const tools = saved.compile?.tools.map((t) => t.name) ?? [];
      this.toasts.show({
        tone: 'success',
        title: `Template ${e.name} installed`,
        body: tools.length
          ? `${joinAnd(tools)} ${tools.length === 1 ? 'is' : 'are'} Off until you add ${tools.length === 1 ? 'it' : 'them'} to an endpoint.`
          : 'Its tools are Off until you add them to an endpoint.',
      });
    } catch (err) {
      this.error.set(failureText(err, 'This build of the gateway core can’t install catalog templates yet.'));
    } finally {
      this.busy.set(false);
    }
  }
}
