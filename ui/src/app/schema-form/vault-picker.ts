import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DOCUMENT, DestroyRef, ElementRef, Injector, TemplateRef, afterRenderEffect, computed, effect, inject, input, linkedSignal, output, signal, untracked, viewChild } from '@angular/core';
import { isApiError } from '../core/api/api-error';
import { SecretStore, VaultReferences } from '../core/api/secrets.api';
import { GwAriaDisabled } from '../kit/aria-disabled';
import { MenuService } from '../kit/menu.service';
import { SchemaNode } from './compile';
import { SchemaForm } from './schema-form';
import { selectableVaultKey, vaultReferenceParts } from './secret-sources';
import { VaultReferenceCatalog } from './vault-reference-catalog';

/**
 * The field of a `vault://` reference with automatically loaded path/key suggestions, from the Vault chosen in the
 * field's menu (`lead`, put before the input) or the only one connected.
 */
@Component({
  selector: 'gw-vault-picker',
  imports: [GwAriaDisabled, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './vault-picker.html',
  host: { class: 'gw-field' },
})
export class GwVaultPicker {
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly doc = inject(DOCUMENT);
  private readonly menus = inject(MenuService);
  readonly form = input.required<SchemaForm>();
  readonly node = input.required<SchemaNode>();
  readonly describedBy = input<string | null>(null);
  readonly invalid = input(false);
  /** What goes before the input in its group: the field's menu of where its value comes from. */
  readonly lead = input<TemplateRef<unknown> | null>(null);
  /** The person typed: the field may become another control (a value instead of a reference). */
  readonly typed = output<void>();
  readonly fieldId = computed(() => this.form().id(this.node()));
  readonly id = computed(() => `${this.fieldId()}-vault`);
  readonly field = viewChild<ElementRef<HTMLInputElement>>('field');
  readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  /** The Vault chosen in the field's menu; without one, the only Vault connected. */
  readonly store = computed(() => this.form().fieldStore(this.node()) ?? '');
  readonly stores = signal<readonly SecretStore[]>([]);
  readonly selectedStore = computed(() => {
    const stores = this.stores();
    const chosen = this.store();
    if (!chosen) return stores.length === 1 ? stores[0] : null;
    return stores.find((store) => store.id === chosen || store.name === chosen) ?? null;
  });
  readonly storesBusy = signal(false);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly result = signal<VaultReferences | null>(null);
  /** The reference as typed, with its scheme. */
  readonly text = computed(() => this.form().fieldText(this.node()));
  readonly matches = computed(() => {
    const typed = this.text().trim();
    const parts = vaultReferenceParts(typed.toLowerCase().startsWith('vault://') ? typed.slice('vault://'.length) : typed);
    const query = (parts.uri.replace(/\?version=[1-9][0-9]*$/, '') + (parts.key ? `#${parts.key}` : '')).trim().toLowerCase();
    return (this.result()?.references ?? []).filter((ref) => ref.slice('vault://'.length).toLowerCase().includes(query));
  });
  readonly visible = computed(() => this.matches().slice(0, 50));
  readonly activeIndex = linkedSignal(() => { this.text(); this.result(); return -1; });
  private readonly requestedOpen = signal(false);
  readonly expanded = computed(() => !!this.panel() && this.menus.isOpen(this.panel()!.nativeElement));
  readonly activeId = computed(() => this.expanded() && this.activeIndex() >= 0 ? `${this.id()}-option-${this.activeIndex()}` : null);
  readonly status = computed(() => {
    if (this.storesBusy()) return 'Loading Vault stores…';
    if (this.error()) return this.error()!;
    if (!this.stores().length) return 'Connect Vault or OpenBao in Secrets to load reference suggestions.';
    if (!this.selectedStore()) return this.store() ? `${this.store()} isn't connected. Choose a connected Vault in the field's menu.`
      : `Choose a Vault in the field's menu to load its keys. Without one, each Vault is asked in turn for ${this.text().trim() || 'the reference'}.`;
    if (this.loading()) return 'Loading Vault paths and key names…';
    const result = this.result();
    if (!result) return 'Reference suggestions are not loaded. Refresh the list to try again.';
    if (!result.references.length) return result.complete ? 'No readable Vault keys were found.' : 'No Vault keys could be loaded. See the details below.';
    const count = this.matches().length;
    if (!count) return 'No matching Vault keys. Edit the search or enter a reference manually.';
    return `${count} matching ${count === 1 ? 'key' : 'keys'}${count > 50 ? '; showing the first 50. Type to narrow the list' : '. Choose a suggestion to insert its path and key'}.${result.complete ? '' : ' The list is incomplete.'}`;
  });
  private storesRequest = 0;
  private indexRequest = 0;

  // Schema-only previews still work when no API client is configured.
  private get catalog(): VaultReferenceCatalog { return this.injector.get(VaultReferenceCatalog); }

  constructor() {
    effect(() => {
      const form = this.form();
      const node = this.node();
      untracked(() => void this.loadStores(form, node));
    });
    effect(() => {
      const store = this.selectedStore();
      const form = this.form();
      const node = this.node();
      untracked(() => {
        if (store) void this.loadReferences(store, form, node);
        else { this.indexRequest++; this.result.set(null); this.loading.set(false); this.close(); }
      });
    });
    afterRenderEffect(() => {
      const visible = this.visible();
      const open = this.requestedOpen();
      const field = this.field()?.nativeElement;
      const panel = this.panel()?.nativeElement;
      untracked(() => {
        if (!field || !panel) return;
        if (open && visible.length && this.doc.activeElement === field) {
          if (!this.menus.isOpen(panel)) this.menus.openFor(field, panel, false, true);
          else this.menus.place();
        } else if (this.menus.isOpen(panel)) this.menus.close(false);
      });
    });
    this.destroyRef.onDestroy(() => this.close());
  }

  private current(form: SchemaForm, node: SchemaNode): boolean {
    return !this.destroyRef.destroyed && this.form() === form && this.node() === node && form.visible(node)
      && form.fieldKind(node) === 'vault';
  }

  private async loadStores(form: SchemaForm, node: SchemaNode, refresh = false): Promise<void> {
    const request = ++this.storesRequest;
    this.storesBusy.set(true);
    this.error.set(null);
    this.stores.set([]);
    this.result.set(null);
    try {
      const stores = await this.catalog.stores(refresh);
      if (!this.current(form, node) || request !== this.storesRequest) return;
      this.stores.set(stores);
    } catch (error) {
      if (this.current(form, node) && request === this.storesRequest) this.error.set(isApiError(error) ? error.detail : 'Could not load Vault stores. Refresh the list to try again.');
    } finally {
      if (this.current(form, node) && request === this.storesRequest) this.storesBusy.set(false);
    }
  }

  private async loadReferences(store: SecretStore, form: SchemaForm, node: SchemaNode, refresh = false): Promise<void> {
    const request = ++this.indexRequest;
    this.result.set(null);
    this.error.set(null);
    this.loading.set(true);
    const current = () => this.current(form, node) && request === this.indexRequest && this.selectedStore()?.id === store.id;
    try {
      const result = await this.catalog.references(store, refresh);
      if (!current()) return;
      const references = [...new Set(result.references)].filter((ref) => {
        const parts = vaultReferenceParts(ref);
        return parts.uri.startsWith('vault://') && selectableVaultKey(parts.uri, parts.key);
      }).sort();
      this.result.set({ ...result, references });
    } catch (error) {
      if (current()) this.error.set(isApiError(error) ? error.detail : 'Could not load Vault keys. Refresh the list to try again.');
    } finally {
      if (current()) this.loading.set(false);
    }
  }

  refresh(): void {
    if (this.storesBusy() || this.loading()) return;
    const store = this.selectedStore();
    if (store) void this.loadReferences(store, this.form(), this.node(), true);
    else void this.loadStores(this.form(), this.node(), true);
  }

  onInput(input: HTMLInputElement): void {
    this.form().setFieldText(this.node(), input.value);
    this.typed.emit();
    this.open();
  }

  open(): void {
    this.requestedOpen.set(true);
    const field = this.field()?.nativeElement;
    const panel = this.panel()?.nativeElement;
    // Keyboard selection must work even when Enter follows ArrowDown before the next render.
    if (field && panel && this.visible().length && this.doc.activeElement === field && !this.menus.isOpen(panel)) {
      this.menus.openFor(field, panel, false, true);
    }
  }
  close(): void {
    this.requestedOpen.set(false);
    const panel = this.panel()?.nativeElement;
    if (panel && this.menus.isOpen(panel)) this.menus.close(false);
  }

  choose(ref: string): void {
    if (!this.result()?.references.includes(ref)) return;
    const previous = vaultReferenceParts(this.form().fieldReference(this.node()));
    const next = vaultReferenceParts(ref);
    const version = previous.uri.match(/\?version=[1-9][0-9]*$/)?.[0] ?? '';
    const uri = version && previous.uri.slice(0, -version.length) === next.uri ? `${next.uri}${version}#${next.key}` : ref;
    this.form().setFieldText(this.node(), uri);
    this.close();
    this.field()?.nativeElement.focus();
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.isComposing) return;
    if (event.key === 'Escape') { if (this.expanded()) event.preventDefault(); this.close(); return; }
    if (event.key === 'Tab') { this.close(); return; }
    const options = this.visible();
    if (!options.length) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      this.open();
      const current = this.expanded() ? this.activeIndex() : -1;
      const next = event.key === 'ArrowDown' ? (current + 1) % options.length : (current <= 0 ? options.length : current) - 1;
      this.activeIndex.set(next);
      this.panel()?.nativeElement.querySelectorAll<HTMLElement>('[role="option"]')[next]?.scrollIntoView?.({ block: 'nearest' });
    } else if (event.key === 'Enter' && this.expanded()) {
      event.preventDefault();
      this.choose(options[Math.max(this.activeIndex(), 0)]);
    }
  }

  /** Test reference: in the Vault chosen in the field's menu, else each in turn (the core asks them). */
  testReference(): void {
    const store = this.form().fieldStore(this.node());
    this.form().emit({ kind: 'test', node: this.node(), ref: this.form().fieldReference(this.node()), ...(store ? { store } : {}) });
  }
}
