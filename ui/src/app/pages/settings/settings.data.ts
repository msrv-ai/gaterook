import { DestroyRef, Injectable, WritableSignal, computed, inject, signal } from '@angular/core';
import { isNotAvailable } from '../../core/api/api-error';
import { ApprovalDetail, ApprovalItem, ApprovalsApi } from '../../core/api/approvals.api';
import { ClientsApi, ClientsView } from '../../core/api/clients.api';
import { CoreApi, CoreInfo, Health } from '../../core/api/core.api';
import { EndpointSummary, EndpointsApi, TokenRow, TokensApi } from '../../core/api/endpoints.api';
import { ChannelKind, ChannelPatch, NotificationChannel, Notifications, NotificationsApi, NotificationsSent } from '../../core/api/notifications.api';
import { OverviewApi } from '../../core/api/overview.api';
import { MasterKeyApi, MasterKeyStatus, SecretsApi } from '../../core/api/secrets.api';
import {
  AuditExportApi,
  AuditExportDestination,
  BackupCopy,
  BackupStatus,
  SettingsApi,
  SettingsSection,
  SettingsSectionName,
} from '../../core/api/settings.api';
import { OAuthApi, OAuthGrant, TunnelApi, TunnelProviderId, TunnelState } from '../../core/api/tunnel.api';
import { JsonObject } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { SettingsStore } from '../../core/stores/settings.store';
import { TunnelStore } from '../../core/stores/tunnel.store';
import { exportBlocked } from './settings-view';

/**
 * One part of the page as loaded: its value (null until the first answer), why its first load failed, and whether
 * this core has the route at all (`unavailable`: the backend step of the area isn't merged — the section says so and
 * never shows made-up data).
 */
export interface Part<T> {
  readonly value: T | null;
  readonly error: unknown;
  readonly unavailable: boolean;
}

const EMPTY: Part<never> = { value: null, error: null, unavailable: false };

export type Sections = { readonly [name: string]: SettingsSection };

/**
 * The Settings page's data (ARCHITECTURE.md §6 settings, §8.4): the typed sections (`/api/settings`), the core
 * (`/api/core/info`, `/api/health`), the database backup (`/api/backup`), the master key (`/api/master-key`, and
 * `/api/secrets` for how many references don't need it), the endpoints and their expiring tokens, the clients (who has
 * to connect again after a port change), today's calls (the retention estimate), and the areas of later steps:
 * notifications (K2), the tunnel and OAuth sign-ins (K3), audit export (K5).
 *
 * Each part loads on its own: one that fails, or that this core doesn't have, leaves the others working. Reloads are
 * coalesced (one in flight, one more after it). Live: `tunnel.state`, `notify.state` / `notify.link.updated`,
 * `oauth.consent.resolved`, the end of a master-key rotation, and an integrity alert on a policy (Approval rules
 * shows the policies' approval settings).
 *
 * Values a person types for a secret (a bot token, a passphrase) go out in one request and are never kept here.
 */
@Injectable()
export class SettingsData {
  private readonly settingsApi = inject(SettingsApi);
  private readonly exportApi = inject(AuditExportApi);
  private readonly coreApi = inject(CoreApi);
  private readonly masterKeyApi = inject(MasterKeyApi);
  private readonly secretsApi = inject(SecretsApi);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly tokensApi = inject(TokensApi);
  private readonly clientsApi = inject(ClientsApi);
  private readonly overviewApi = inject(OverviewApi);
  private readonly approvalsApi = inject(ApprovalsApi);
  private readonly notificationsApi = inject(NotificationsApi);
  private readonly tunnelApi = inject(TunnelApi);
  private readonly oauthApi = inject(OAuthApi);
  private readonly events = inject(EventStream);
  private readonly store = inject(SettingsStore);
  private readonly tunnelStore = inject(TunnelStore);

  private readonly settingsSignal = signal<Part<Sections>>(EMPTY);
  private readonly infoSignal = signal<Part<CoreInfo>>(EMPTY);
  private readonly healthSignal = signal<Part<Health>>(EMPTY);
  private readonly backupSignal = signal<Part<BackupStatus>>(EMPTY);
  private readonly masterKeySignal = signal<Part<MasterKeyStatus>>(EMPTY);
  private readonly referencesSignal = signal<number | null>(null);
  private readonly endpointsSignal = signal<Part<EndpointSummary[]>>(EMPTY);
  private readonly tokensSignal = signal<ReadonlyMap<string, readonly TokenRow[]>>(new Map());
  private readonly clientsSignal = signal<ClientsView | null>(null);
  private readonly callsSignal = signal<number | null>(null);
  private readonly notificationsSignal = signal<Part<Notifications>>(EMPTY);
  private readonly slackChannelsSignal = signal<readonly { readonly id: string; readonly name: string }[]>([]);
  private readonly tunnelSignal = signal<Part<TunnelState>>(EMPTY);
  /** Settings › Tunnel as a section (`GET /api/settings/tunnel`): the provider form's `version`, what is stored. */
  private readonly tunnelSettingsSignal = signal<Part<SettingsSection>>(EMPTY);
  private readonly grantsSignal = signal<Part<OAuthGrant[]>>(EMPTY);
  private readonly exportsSignal = signal<Part<AuditExportDestination[]>>(EMPTY);
  private readonly staleSignal = signal<unknown>(null);
  private readonly refreshingSignal = signal(false);

  readonly settings = this.settingsSignal.asReadonly();
  readonly info = this.infoSignal.asReadonly();
  readonly health = this.healthSignal.asReadonly();
  readonly backup = this.backupSignal.asReadonly();
  readonly masterKey = this.masterKeySignal.asReadonly();
  /** Secret references (Vault, GCP SM, AWS SM): they don't need the master key. */
  readonly references = this.referencesSignal.asReadonly();
  readonly endpoints = this.endpointsSignal.asReadonly();
  /** The tokens of each endpoint the core says has one expiring soon. */
  readonly tokens = this.tokensSignal.asReadonly();
  readonly clients = this.clientsSignal.asReadonly();
  /** Calls in the last 24 hours (Overview's count), for the retention estimate. */
  readonly calls24h = this.callsSignal.asReadonly();
  readonly notifications = this.notificationsSignal.asReadonly();
  readonly slackChannels = this.slackChannelsSignal.asReadonly();
  readonly tunnel = this.tunnelSignal.asReadonly();
  readonly tunnelSettings = this.tunnelSettingsSignal.asReadonly();
  readonly grants = this.grantsSignal.asReadonly();
  readonly exports = this.exportsSignal.asReadonly();
  /** Why the last reload failed while older data is on screen. */
  readonly stale = this.staleSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();

  /** The page needs the sections; everything else fills in where it arrives. */
  readonly loaded = computed(() => {
    const s = this.settingsSignal();
    return s.value !== null || s.error !== null || s.unavailable;
  });

  /** The endpoint published for claude.ai (the tunnel's, else the one marked published). */
  readonly published = computed(() => {
    const t = this.tunnelSignal().value;
    if (t?.endpoint) return t.endpoint;
    return this.endpointsSignal().value?.find((e) => e.published)?.slug ?? null;
  });

  private inFlight: Promise<void> | null = null;
  private again = false;
  private stopped = false;
  private readonly unsubscribe: (() => void)[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  start(): Promise<void> {
    const on = this.events.on.bind(this.events);
    this.unsubscribe.push(
      on('tunnel.state', () => void this.reloadPart(this.tunnelSignal, () => this.tunnelApi.get())),
      on('notify.state', () => void this.reloadNotifications()),
      on('notify.link.updated', () => void this.reloadNotifications()),
      on('oauth.consent.resolved', () => void this.reloadPart(this.grantsSignal, () => this.oauthApi.grants())),
      on('master-key.rotation', (e) => {
        if (e.state === 'done' || e.state === 'failed') void this.reloadPart(this.masterKeySignal, () => this.masterKeyApi.status());
      }),
      // Approval rules shows the policies' approval settings: a policy row that failed its check changes them.
      on('integrity.alert', (e) => {
        if (e.kind === 'policy') void this.reloadSettings();
      }),
    );
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    this.unsubscribe.splice(0).forEach((u) => u());
  }

  /** Reloads every part now; while one runs, one more follows it (a change that came meanwhile is in neither). */
  refresh(): Promise<void> {
    if (this.inFlight) {
      this.again = true;
      return this.inFlight;
    }
    this.refreshingSignal.set(true);
    this.inFlight = this.load().finally(() => {
      this.inFlight = null;
      this.refreshingSignal.set(false);
      if (this.again && !this.stopped) {
        this.again = false;
        void this.refresh();
      }
    });
    return this.inFlight;
  }

  section(name: SettingsSectionName): SettingsSection | null {
    return this.settingsSignal().value?.[name] ?? null;
  }

  private async load(): Promise<void> {
    const failures: unknown[] = [];
    const part = <T>(target: WritableSignal<Part<T>>, call: () => Promise<T>) => this.loadPart(target, call, failures);
    await Promise.all([
      part(this.settingsSignal, () => this.settingsApi.all()).then(() => this.publishSections()),
      part(this.infoSignal, () => this.coreApi.info()),
      part(this.healthSignal, () => this.coreApi.health()),
      part(this.backupSignal, () => this.settingsApi.backup()),
      part(this.masterKeySignal, () => this.masterKeyApi.status()),
      part(this.endpointsSignal, () => this.endpointsApi.list()).then(() => this.loadTokens()),
      part(this.notificationsSignal, () => this.notificationsApi.get()).then(() => this.loadSlackChannels()),
      part(this.tunnelSignal, () => this.tunnelApi.get()),
      part(this.tunnelSettingsSignal, () => this.settingsApi.get('tunnel')),
      part(this.grantsSignal, () => this.oauthApi.grants()),
      part(this.exportsSignal, () => this.exportApi.list()),
      // Facts that only refine a sentence: without them the sentence is shorter, never wrong.
      this.secretsApi.list().then(
        (rows) => this.alive(() => this.referencesSignal.set(rows.filter((r) => r.source !== 'local').length)),
        () => undefined,
      ),
      this.clientsApi.list().then(
        (view) => this.alive(() => this.clientsSignal.set(view)),
        () => undefined,
      ),
      this.overviewApi.get().then(
        (o) => this.alive(() => this.callsSignal.set(o.stats.calls24h)),
        () => undefined,
      ),
    ]);
    if (!this.stopped) this.staleSignal.set(failures[0] ?? null);
  }

  private async loadPart<T>(target: WritableSignal<Part<T>>, call: () => Promise<T>, failures: unknown[] | null): Promise<void> {
    try {
      const value = await call();
      if (!this.stopped) target.set({ value, error: null, unavailable: false });
    } catch (error) {
      if (this.stopped) return;
      if (isNotAvailable(error)) target.set({ value: null, error: null, unavailable: true });
      else if (target().value !== null) failures?.push(error); // older data stays; the page says it may be out of date
      else target.set({ value: null, error, unavailable: false });
    }
  }

  private reloadPart<T>(target: WritableSignal<Part<T>>, call: () => Promise<T>): Promise<void> {
    return this.loadPart(target, call, null);
  }

  private alive(f: () => void): void {
    if (!this.stopped) f();
  }

  /** The shell's copy of the sections (the titlebar reads General) follows what this page loaded or saved. */
  private publishSections(): void {
    const all = this.settingsSignal().value;
    if (all) Object.values(all).forEach((s) => this.store.put(s));
  }

  private async loadTokens(): Promise<void> {
    const list = this.endpointsSignal().value ?? [];
    const out = new Map<string, readonly TokenRow[]>();
    await Promise.all(
      list
        .filter((e) => e.tokens.expiringSoon > 0)
        .map((e) =>
          this.tokensApi.list(e.slug).then(
            (rows) => void out.set(e.slug, rows),
            () => undefined,
          ),
        ),
    );
    this.alive(() => this.tokensSignal.set(out));
  }

  private async loadSlackChannels(): Promise<void> {
    const slack = this.notificationsSignal().value?.channels.find((c) => c.kind === 'slack');
    if (!slack?.enabled) {
      this.alive(() => this.slackChannelsSignal.set([]));
      return;
    }
    try {
      const list = await this.notificationsApi.slackChannels();
      this.alive(() => this.slackChannelsSignal.set(list));
    } catch {
      // The picker keeps the saved channel only.
    }
  }

  async reloadSettings(): Promise<void> {
    await this.reloadPart(this.settingsSignal, () => this.settingsApi.all());
    this.publishSections();
  }

  async reloadNotifications(): Promise<void> {
    await this.reloadPart(this.notificationsSignal, () => this.notificationsApi.get());
    await this.loadSlackChannels();
  }

  // ── Saving sections ───────────────────────────────────────────────────────────────────────────────────────────

  /**
   * `PUT /api/settings/{section}` with the fields that change and the version this page read (409 when someone saved
   * meanwhile: the sections are read again and the error goes to the caller, so the form can say so).
   */
  async save(name: SettingsSectionName, patch: JsonObject, reason: string | null = null): Promise<SettingsSection> {
    const version = this.section(name)?.version ?? 0;
    try {
      const saved = await this.settingsApi.update(name, { ...patch, version, ...(reason ? { reason } : {}) });
      this.putSection(saved);
      return saved;
    } catch (e) {
      if ((e as { status?: number }).status === 409) void this.reloadSettings();
      throw e;
    }
  }

  private putSection(saved: SettingsSection): void {
    this.settingsSignal.update((p) => ({ ...p, value: { ...(p.value ?? {}), [saved.name]: saved } }));
    this.store.put(saved);
  }

  // ── Backup ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** Back up now: into the backup folder (it counts towards the copies kept). */
  async backupNow(): Promise<BackupCopy> {
    const copy = await this.settingsApi.backupNow();
    void this.reloadPart(this.backupSignal, () => this.settingsApi.backup());
    return copy;
  }

  async backupKey(saveToken: string, passphrase: string | null): Promise<{ readonly path: string; readonly passphrase: boolean; readonly warnings: string[] }> {
    const result = await this.masterKeyApi.backup(saveToken, passphrase);
    void this.reloadPart(this.masterKeySignal, () => this.masterKeyApi.status());
    return result;
  }

  // ── Notifications (K2) ────────────────────────────────────────────────────────────────────────────────────────

  /**
   * `PUT /api/notifications/{kind}` with the fields that change (flat, as `NotificationsController.ChannelBody` takes
   * them) and the version this page read; the core checks a token before it saves (422 with the test result). The
   * channel keeps its place in the list. 409 (saved meanwhile) reads the channels again for the next try.
   */
  async updateChannel(kind: ChannelKind, patch: ChannelPatch): Promise<NotificationChannel> {
    const current = this.notificationsSignal().value?.channels.find((c) => c.kind === kind);
    try {
      const saved = await this.notificationsApi.update(kind, { ...patch, version: current?.version ?? 0 });
      this.notificationsSignal.update((p) =>
        p.value ? { ...p, value: { ...p.value, channels: p.value.channels.map((c) => (c.kind === kind ? saved : c)) } } : p,
      );
      if (kind === 'slack') void this.loadSlackChannels();
      return saved;
    } catch (e) {
      if ((e as { status?: number }).status === 409) void this.reloadNotifications();
      throw e;
    }
  }

  async disconnectSlack(): Promise<void> {
    await this.notificationsApi.disconnectSlack();
    await this.reloadNotifications();
  }

  /** "Send test notification": what each channel did with it (`sent`, `failed` with the reason, `off`). */
  testNotifications(): Promise<NotificationsSent> {
    return this.notificationsApi.sendTest();
  }

  /** The requests waiting now (Preview messages shows one of them; the port dialog counts them). */
  async pending(): Promise<ApprovalItem[]> {
    return (await this.approvalsApi.list('pending')).items;
  }

  approval(id: string): Promise<ApprovalDetail> {
    return this.approvalsApi.get(id);
  }

  // ── Tunnel and OAuth (K3) ─────────────────────────────────────────────────────────────────────────────────────

  async setCallers(anthropicOnly: boolean): Promise<void> {
    await this.settingsApi.update('tunnel/callers', { anthropicOnly });
    await this.reloadPart(this.tunnelSignal, () => this.tunnelApi.get());
  }

  /**
   * Provider (`PUT /api/settings/tunnel {provider, version, ngrokAuthtoken?}`): the version of the section this page
   * read — the core refuses a save without one. An ngrok authtoken goes out in this one request and is stored as a
   * secret of the tunnel; nobody reads it back. 409 (changed meanwhile, or the tunnel is on) reads the section again.
   */
  async saveProvider(provider: TunnelProviderId, ngrokAuthtoken: string | null = null): Promise<void> {
    const version = this.tunnelSettingsSignal().value?.version ?? 0;
    try {
      const saved = await this.settingsApi.update('tunnel', { provider, version, ...(ngrokAuthtoken ? { ngrokAuthtoken } : {}) });
      this.tunnelSettingsSignal.set({ value: saved, error: null, unavailable: false });
    } catch (e) {
      if ((e as { status?: number }).status === 409) void this.reloadPart(this.tunnelSettingsSignal, () => this.settingsApi.get('tunnel'));
      throw e;
    }
    await this.reloadPart(this.tunnelSignal, () => this.tunnelApi.get());
  }

  publishPreview(slug: string): ReturnType<TunnelApi['publishPreview']> {
    return this.tunnelApi.publishPreview(slug);
  }

  async publish(slug: string): Promise<void> {
    const state = await this.tunnelApi.publish(slug);
    this.tunnelSignal.set({ value: state, error: null, unavailable: false });
    void this.tunnelStore.refresh();
  }

  async stopTunnel(): Promise<void> {
    await this.tunnelStore.stop();
    await this.reloadPart(this.tunnelSignal, () => this.tunnelApi.get());
  }

  async revokeGrant(id: string): Promise<void> {
    await this.oauthApi.revoke(id);
    await this.reloadPart(this.grantsSignal, () => this.oauthApi.grants());
  }

  // ── Audit export (K5) ─────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Export now / Retry: one destination, or every one that can run (enabled, not changed outside GateRook, not
   * a demo — the core refuses those with 409). Each runs on its own: the ones that didn't start come back with the
   * core's reason, the others ran.
   */
  async runExport(id: string | null): Promise<readonly { readonly id: string; readonly error: unknown }[]> {
    const list = this.exportsSignal().value ?? [];
    const ids = id ? [id] : list.filter(runnableExport).map((d) => d.id);
    const results = await Promise.allSettled(ids.map((i) => this.exportApi.run(i)));
    await this.reloadPart(this.exportsSignal, () => this.exportApi.list());
    return results.flatMap((r, i) => (r.status === 'rejected' ? [{ id: ids[i], error: r.reason as unknown }] : []));
  }
}

/** Whether Export now runs a destination: on, its row verified, not one of the demo world's (shown, never called). */
export function runnableExport(d: AuditExportDestination): boolean {
  return d.enabled && exportBlocked(d) === null;
}
