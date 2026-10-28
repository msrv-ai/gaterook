import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormField, form, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { SecretStore, SecretStoreCreate, SecretStoreKind } from '../../core/api/secrets.api';
import { JsonObject } from '../../core/api/types';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwCopy } from '../../kit/copy';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwToggle } from '../../kit/disclosure';
import { GwIcon } from '../../kit/icons';
import { GwReveal } from '../../kit/reveal';
import { ToastService } from '../../kit/toast.service';
import { GwJump } from './jump';
import { SecretsData } from './secrets.data';
import {
  STORE_KINDS,
  StoreKindInfo,
  TextPart,
  ToastText,
  connectedToast,
  connectionsQuery,
  disconnectLead,
  disconnectedToast,
  kindOfRef,
  storeAnchors,
  storeConnections,
  storeDescription,
  storeIssuesText,
  storeKind,
  storeReferences,
  storeSignIn,
  storeStatus,
  storeTestToast,
  storesSummary,
  waitingFor,
  waitingText,
} from './secrets-view';
import { AWS_REGIONS, ConnectModel, FIELD_POINTERS, SecretKey, connectRequest, emptyConnectModel, externalId, fieldProblem, isPlainHttp, storeName } from './store-connect';

const NO_ANSWER = 'The core didn’t answer. Try again.';

/** One card of External stores: a connected store, or the dashed card of a kind that has none. */
interface StoreCard {
  readonly key: string;
  readonly anchor: string;
  readonly info: StoreKindInfo;
  readonly store: SecretStore | null;
  readonly title: string;
}

/**
 * Secrets › External stores (design/pages/secrets.html `#external-stores`; ARCHITECTURE.md §6 `/api/secret-stores`,
 * step S1): HashiCorp Vault / OpenBao, GCP Secret Manager and AWS Secrets Manager, read at call time and never copied
 * here. A connected store shows how it signs in and what reads through it, with Test and Disconnect; a kind without a
 * store is a dashed card with Connect and the references that wait for it. Until the core has the stores' API (S1 not
 * merged) the section says they aren't available yet.
 *
 * - Connect (kit `#dlg-connect-aws`, the same form for the other kinds): the kind's settings, its sign-in values typed
 *   once into password fields (read only when sent), Test and connect. AWS shows the ExternalId to put into the
 *   role's trust policy before connecting.
 * - Disconnect: `gw-dialog--danger` naming what fails next, Cancel has the focus.
 * - A store changed outside GateRook isn't used until the person keeps it (`gw-dialog--warning`).
 */
@Component({
  selector: 'section[gwExternalStores]',
  imports: [RouterLink, FormField, GwIcon, GwDialog, GwDialogClose, GwToggle, GwCodeBlock, GwCopy, GwReveal, GwJump],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-section', id: 'external-stores', 'aria-labelledby': 'es-title' },
  templateUrl: './external-stores.html',
})
export class GwExternalStores {
  protected readonly data = inject(SecretsData);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly doc = inject(DOCUMENT);

  private readonly connectDialog = viewChild.required<GwDialog>('connectDialog');
  private readonly plainHttpDialog = viewChild.required<GwDialog>('plainHttpDialog');
  private readonly disconnectDialog = viewChild.required<GwDialog>('disconnectDialog');
  private readonly keepDialog = viewChild.required<GwDialog>('keepDialog');
  /** Sign-in values: typed once, read from the field when sent, never in the form model. */
  private readonly awsSecret = viewChild<ElementRef<HTMLInputElement>>('awsSecret');
  private readonly vaultSecretId = viewChild<ElementRef<HTMLInputElement>>('vaultSecretId');
  private readonly vaultToken = viewChild<ElementRef<HTMLInputElement>>('vaultToken');
  private readonly gcpKey = viewChild<ElementRef<HTMLTextAreaElement>>('gcpKey');

  protected readonly regions = AWS_REGIONS;
  protected readonly storeSignIn = storeSignIn;
  protected readonly storeReferences = storeReferences;
  protected readonly storeStatus = storeStatus;
  protected readonly storeDescription = storeDescription;
  protected readonly storeIssuesText = storeIssuesText;
  protected readonly connectionsQuery = connectionsQuery;
  protected readonly storeConnections = storeConnections;

  protected readonly stores = computed(() => this.data.stores().value);
  protected readonly summary = computed(() => storesSummary(this.stores() ?? []));

  /** Kit order: Vault, GCP SM, AWS SM; each store of a kind, or one dashed card for a kind without any. */
  protected readonly cards = computed<StoreCard[]>(() => {
    const stores = this.stores() ?? [];
    const anchors = storeAnchors(stores);
    return STORE_KINDS.flatMap((info): StoreCard[] => {
      const mine = stores.filter((s) => s.kind === info.kind);
      if (!mine.length) return [{ key: info.kind, anchor: `store-${info.kind.replace(/-sm$/, '')}`, info, store: null, title: info.title }];
      return mine.map((s) => ({
        key: s.id,
        anchor: anchors.get(s.name) ?? s.id,
        info,
        store: s,
        title: mine.length > 1 ? `${info.title} · ${s.name}` : info.title,
      }));
    });
  });

  /** References of each kind that wait for its store (they can't be resolved until it is connected). */
  protected waiting(kind: SecretStoreKind): TextPart[] {
    return waitingText(waitingFor(kind, this.data.rows().value ?? []));
  }

  // ── Test ──────────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly testing = signal<ReadonlySet<string>>(new Set());

  protected async test(card: StoreCard): Promise<void> {
    const s = card.store;
    if (!s || this.testing().has(s.id) || s.verified === false) return;
    this.testing.update((t) => new Set([...t, s.id]));
    this.announcer.announce(`Testing ${card.title}`);
    try {
      this.toast(storeTestToast(card.title, await this.data.testStore(s.id)));
    } catch (err) {
      this.toast({ tone: 'danger', title: `${card.title} wasn't tested`, body: this.errorText(err) });
    } finally {
      this.testing.update((t) => {
        const next = new Set(t);
        next.delete(s.id);
        return next;
      });
    }
  }

  // ── Connect ───────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly connecting = signal<StoreKindInfo | null>(null);
  protected readonly connectBusy = signal(false);
  protected readonly pendingHttpAddress = signal<string | null>(null);
  protected readonly connectError = signal<{ readonly title: string; readonly body: string } | null>(null);
  protected readonly submitted = signal(false);
  /** Sign-in values the person left empty (their fields aren't in the model). */
  protected readonly secretErrors = signal<{ readonly [key: string]: string }>({});
  /** Rules the core reported, by form key (422 with a JSON Pointer). */
  protected readonly serverErrors = signal<{ readonly [key: string]: string }>({});

  protected readonly model = signal<ConnectModel>(emptyConnectModel());
  protected readonly connectForm = form(
    this.model,
    (p) => {
      // One rule per key: the kind decides which keys are on the form (fieldProblem knows).
      for (const key of Object.keys(emptyConnectModel()) as (keyof ConnectModel)[]) {
        validate(p[key], () => {
          const kind = this.connecting()?.kind;
          const problem = kind ? fieldProblem(kind, key, this.model()) : null;
          return problem ? { kind: 'invalid', message: problem } : null;
        });
      }
    },
    { injector: this.injector },
  );

  /** A field's error once the person tried to send (or the core named it). */
  protected fieldError(key: keyof ConnectModel | SecretKey): string | null {
    const server = this.serverErrors()[key];
    if (server) return server;
    if (!this.submitted()) return null;
    const secret = this.secretErrors()[key];
    if (secret) return secret;
    if (!(key in this.model())) return null;
    return this.connectForm[key as keyof ConnectModel]().errors()[0]?.message ?? null;
  }

  protected openConnect(info: StoreKindInfo, e: Event): void {
    const rows = this.data.rows().value ?? [];
    // AWS: the region a waiting reference names (aws-sm://eu-central-1/…), so the store resolves it at once.
    const waitingRef = rows.find((r) => r.state === 'not-connected' && kindOfRef(r.ref) === info.kind)?.ref ?? null;
    const region = waitingRef && info.kind === 'aws-sm' ? (/^aws-sm:\/\/([a-z0-9-]+)\//.exec(waitingRef)?.[1] ?? null) : null;
    this.model.set({ ...emptyConnectModel(), region: region && AWS_REGIONS.includes(region) ? region : 'us-east-1', externalId: externalId() });
    this.connecting.set(info);
    this.connectBusy.set(false);
    this.connectError.set(null);
    this.submitted.set(false);
    this.secretErrors.set({});
    this.serverErrors.set({});
    this.connectDialog().openAfterRender(e.currentTarget as HTMLElement);
  }

  /** Test and connect: create the store, then test it; a failed test leaves it connected and failing. */
  protected async connect(e: Event): Promise<void> {
    e.preventDefault();
    if (this.pendingHttpAddress()) return;
    await this.submitConnect();
  }

  private async submitConnect(confirmedHttpAddress?: string): Promise<void> {
    const info = this.connecting();
    if (!info || this.connectBusy()) return;
    this.submitted.set(true);
    this.serverErrors.set({});
    this.connectError.set(null);
    const secrets = this.readSecrets(info.kind);
    if (this.connectForm().invalid() || Object.keys(this.secretErrors()).length) {
      this.focusFirstError();
      return;
    }
    const address = this.model().address.trim();
    const plainHttp = info.kind === 'vault' && isPlainHttp(address);
    if (plainHttp && confirmedHttpAddress !== address) {
      this.pendingHttpAddress.set(address);
      this.plainHttpDialog().openAfterRender(this.doc.querySelector<HTMLElement>('#dlg-connect-store button[type="submit"]'));
      return;
    }
    const taken = (this.stores() ?? []).map((s) => s.name);
    const request: SecretStoreCreate = connectRequest(info.kind, storeName(info.kind, this.model(), taken), this.model(), secrets, plainHttp);
    this.connectBusy.set(true);
    try {
      const { store, test } = await this.data.connectStore(request);
      this.clearSecrets();
      this.connectDialog().close('connected');
      this.toast(connectedToast(info.title, store, test));
    } catch (err) {
      this.applyError(err);
    } finally {
      this.connectBusy.set(false);
    }
  }

  protected confirmPlainHttp(): void {
    if (!this.pendingHttpAddress() || this.connectBusy()) return;
    this.plainHttpDialog().close('confirmed');
  }

  protected async onPlainHttpClosed(result: string): Promise<void> {
    const address = this.pendingHttpAddress();
    this.pendingHttpAddress.set(null);
    // Wait for the native close event before a changed address can open a new warning.
    if (result === 'confirmed' && address) await this.submitConnect(address);
  }

  protected onConnectClosed(): void {
    this.plainHttpDialog().close();
    this.pendingHttpAddress.set(null);
    this.clearSecrets();
    this.connecting.set(null);
  }

  /** The sign-in values of the kind's form, from their fields (each required for its sign-in method). */
  private readSecrets(kind: SecretStoreKind): { readonly [pointer: string]: string } {
    const m = this.model();
    const errors: Record<string, string> = {};
    const out: Record<string, string> = {};
    const take = (key: string, pointer: string, el: HTMLInputElement | HTMLTextAreaElement | undefined, missing: string) => {
      const v = el?.value ?? '';
      if (!v) errors[key] = missing;
      else out[pointer] = v;
    };
    if (kind === 'aws-sm' && m.awsSource === 'accessKey') take('secretAccessKey', '/credentials/secretAccessKey', this.awsSecret()?.nativeElement, 'Enter the secret access key.');
    if (kind === 'vault' && m.method === 'approle') take('secretId', '/auth/secretId', this.vaultSecretId()?.nativeElement, 'Enter the AppRole secret ID.');
    if (kind === 'vault' && m.method === 'token') take('token', '/auth/token', this.vaultToken()?.nativeElement, 'Enter the Vault token.');
    if (kind === 'gcp-sm' && m.gcpSource === 'serviceAccountKey') take('serviceAccountKey', '/credentials/serviceAccountKey', this.gcpKey()?.nativeElement, 'Paste the service account key (JSON).');
    this.secretErrors.set(errors);
    return out;
  }

  private clearSecrets(): void {
    for (const ref of [this.awsSecret(), this.vaultSecretId(), this.vaultToken(), this.gcpKey()]) {
      if (ref) ref.nativeElement.value = '';
    }
  }

  // ── Disconnect and keep ───────────────────────────────────────────────────────────────────────────────────────

  protected readonly disconnecting = signal<StoreCard | null>(null);
  protected readonly keeping = signal<StoreCard | null>(null);
  protected readonly actionBusy = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected disconnectLead(card: StoreCard): TextPart[] {
    return card.store ? disconnectLead(card.store, card.title) : [];
  }

  protected askDisconnect(card: StoreCard, e: Event): void {
    this.disconnecting.set(card);
    this.actionBusy.set(false);
    this.actionError.set(null);
    this.disconnectDialog().openAfterRender(e.currentTarget as HTMLElement);
  }

  protected async confirmDisconnect(): Promise<void> {
    const card = this.disconnecting();
    if (!card?.store || this.actionBusy()) return;
    this.actionBusy.set(true);
    this.actionError.set(null);
    const connections = storeConnections(card.store).length;
    try {
      const result = await this.data.disconnectStore(card.store);
      this.disconnectDialog().close('disconnected');
      this.toast(disconnectedToast(card.title, result, connections));
      // The card turned dashed: the keyboard goes to its Connect button (kit: focus stays on the object's place).
      afterNextRender(() => this.doc.querySelector<HTMLElement>(`#${card.anchor} .gw-card__footer .gw-btn`)?.focus(), { injector: this.injector });
    } catch (err) {
      this.actionError.set(this.errorText(err));
    } finally {
      this.actionBusy.set(false);
    }
  }

  protected askKeep(card: StoreCard, e: Event): void {
    this.keeping.set(card);
    this.actionBusy.set(false);
    this.actionError.set(null);
    this.keepDialog().openAfterRender(e.currentTarget as HTMLElement);
  }

  protected async confirmKeep(): Promise<void> {
    const card = this.keeping();
    if (!card?.store || this.actionBusy()) return;
    this.actionBusy.set(true);
    this.actionError.set(null);
    try {
      await this.data.keepStore(card.store);
      this.keepDialog().close('kept');
      this.toast({ tone: 'success', title: `${card.title} is in use again`, body: 'Its references are read from it from the next call.' });
      afterNextRender(() => this.doc.querySelector<HTMLElement>(`#${card.anchor}`)?.focus(), { injector: this.injector });
    } catch (err) {
      this.actionError.set(this.errorText(err));
    } finally {
      this.actionBusy.set(false);
    }
  }

  protected onActionClosed(): void {
    this.disconnecting.set(null);
    this.keeping.set(null);
  }

  // ── Errors ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected retry(): void {
    void this.data.refresh();
  }

  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : NO_ANSWER;
  }

  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/secret-stores → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }

  /** A 422 names its field (`/config/region`, `/region`): the message goes under it; the rest is the dialog's alert. */
  private applyError(err: unknown): void {
    if (isApiError(err) && err.kind === 'unprocessable' && err.errors.length) {
      const byKey: Record<string, string> = {};
      const unplaced: string[] = [];
      for (const fe of err.errors) {
        const key = Object.entries(FIELD_POINTERS).find(([, ptr]) => fe.field === ptr || fe.field.endsWith(ptr))?.[0];
        if (key) byKey[key] = fe.message;
        else unplaced.push(fe.message);
      }
      this.serverErrors.set(byKey);
      if (unplaced.length || !Object.keys(byKey).length) this.connectError.set({ title: err.title, body: unplaced.join(' ') || err.detail });
      this.focusFirstError();
      return;
    }
    this.connectError.set({ title: isApiError(err) ? err.title : "It didn't connect", body: this.errorText(err) });
  }

  private focusFirstError(): void {
    afterNextRender(() => this.doc.querySelector<HTMLElement>('dialog[open] [aria-invalid="true"]')?.focus(), { injector: this.injector });
  }

  private toast(t: ToastText): void {
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body, timeout: t.timeout });
  }

  /** A string of a store's config for the template. */
  protected configString(s: SecretStore, path: string): string | null {
    let node: unknown = s.config;
    for (const part of path.split('.')) node = node && typeof node === 'object' ? (node as JsonObject)[part] : undefined;
    return typeof node === 'string' && node ? node : null;
  }

  protected kindInfo(kind: string): StoreKindInfo | null {
    return storeKind(kind);
  }
}
