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
import { NgTemplateOutlet } from '@angular/common';
import { FormField, form, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { SecretRow } from '../../core/api/secrets.api';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwToggle } from '../../kit/disclosure';
import { GwIcon } from '../../kit/icons';
import { GwSearchClearButton } from '../../kit/search';
import { GwSortRow, SortChange } from '../../kit/table';
import { ToastService } from '../../kit/toast.service';
import { GwJump } from './jump';
import { SecretsData } from './secrets.data';
import {
  DEFAULT_SORT,
  Filters,
  NO_FILTERS,
  RowContext,
  SOURCE_OPTIONS,
  SecretSubject,
  Sort,
  ToastText,
  countText,
  filterRows,
  idOf,
  ownerCell,
  parseSource,
  refProblem,
  refTestLine,
  refTestToast,
  replaceFailedText,
  replaceHint,
  replacedToast,
  rotateLead,
  rotateStartedToast,
  rotatedText,
  rowActions,
  sortRows,
  sourceCell,
  subjectOf,
  versionCell,
} from './secrets-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';
const COMMENT_REQUIRED = 'Add a comment: it is required to change a secret on prod.';

/** The non-secret fields of the row dialogs (Signal Forms); a new value never goes into a model. */
interface RowForm {
  readonly testBefore: boolean;
  readonly reason: string;
  readonly ref: string;
}

/**
 * Secrets › Stored secrets (design/pages/secrets.html `#stored-secrets`; ARCHITECTURE.md §6 `/api/secrets`, §8.4):
 * values encrypted here and references to external stores, write-only — never a value, its length or its prefix.
 *
 * - The toolbar filters at once (search by secret or connection, Source) and its count line says how many show; no
 *   match is the table's own empty row with Clear filters (focus back in the search).
 * - Name and Last rotated sort (kit.js table sort).
 * - Each row: Rotate (only when the connector can set a value at the source; otherwise `aria-disabled` with the
 *   core's reason in a tooltip), Replace (a new value, or another reference), Test (a reference resolves now and
 *   nothing is kept), Connect store (a reference whose store isn't connected), View usage (Activity).
 * - Replace: the new value is typed into a password field (no "show": it replaces a stored one) and read from the
 *   field only when sent; the owner is tested with it before it goes in use; a failed test keeps the old version
 *   and says so in the dialog. On prod a comment is required (it goes to Setup changes).
 */
@Component({
  selector: 'section[gwStoredSecrets]',
  imports: [NgTemplateOutlet, RouterLink, FormField, GwIcon, GwDialog, GwDialogClose, GwSearchClearButton, GwSortRow, GwToggle, GwCodeBlock, GwJump],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-section', id: 'stored-secrets', 'aria-labelledby': 'ss-title' },
  templateUrl: './stored-secrets.html',
})
export class GwStoredSecrets {
  protected readonly data = inject(SecretsData);
  protected readonly time = inject(TimeService);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly doc = inject(DOCUMENT);

  private readonly rotateDialog = viewChild.required<GwDialog>('rotateDialog');
  private readonly replaceDialog = viewChild.required<GwDialog>('replaceDialog');
  private readonly refDialog = viewChild.required<GwDialog>('refDialog');
  private readonly search = viewChild<ElementRef<HTMLInputElement>>('search');
  private readonly valueField = viewChild<ElementRef<HTMLInputElement>>('valueField');

  protected readonly sourceOptions = SOURCE_OPTIONS;
  protected readonly idOf = idOf;

  protected readonly filters = signal<Filters>(NO_FILTERS);
  protected readonly sort = signal<Sort>(DEFAULT_SORT);

  protected readonly rows = computed(() => this.data.rows().value ?? []);
  protected readonly ctx = computed<RowContext>(() => ({ envOf: this.data.envOf(), stores: this.data.stores().value }));
  protected readonly shown = computed(() => sortRows(filterRows(this.rows(), this.filters(), this.ctx()), this.sort()));
  protected readonly countLabel = computed(() => countText(this.shown().length, this.rows().length));
  /** Loaded, and nothing is stored or referenced yet: the section's empty state replaces toolbar and table. */
  protected readonly empty = computed(() => this.data.rows().value?.length === 0);

  /** One view model per shown row; relative times move with the minute. */
  protected readonly view = computed(() => {
    const ctx = this.ctx();
    const now = this.time.minute();
    const testing = this.testing();
    return this.shown().map((row) => ({
      row,
      key: idOf('secret', row.name),
      owner: ownerCell(row, ctx),
      source: sourceCell(row),
      version: versionCell(row),
      rotated: rotatedText(row, now),
      actions: rowActions(row, ctx),
      testing: testing.has(row.id),
    }));
  });

  /** References being tested now (Test in the row: busy in place, the result is a toast). */
  protected readonly testing = signal<ReadonlySet<string>>(new Set());

  // ── Row dialogs ───────────────────────────────────────────────────────────────────────────────────────────────

  /** The secret the open row dialog is about. */
  protected readonly subject = signal<SecretSubject | null>(null);
  protected readonly busy = signal(false);
  protected readonly dialogError = signal<{ readonly title: string; readonly body: string } | null>(null);
  /** The value field's own error (empty value, or the core's word on it). */
  protected readonly valueError = signal<string | null>(null);
  protected readonly refResult = signal<{ readonly ok: boolean; readonly text: string } | null>(null);
  protected readonly refBusy = signal(false);
  protected readonly submitted = signal(false);

  /**
   * The row dialog that is open: only its content renders (the three share the subject and the comment field, whose
   * id must be in the page once), and only in Replace reference does the reference field count.
   */
  protected readonly openDialog = signal<'rotate' | 'replace' | 'ref' | null>(null);
  protected readonly model = signal<RowForm>({ testBefore: true, reason: '', ref: '' });
  protected readonly rowForm = form(
    this.model,
    (p) => {
      validate(p.reason, ({ value }) => (this.subject()?.prod && !value().trim() ? { kind: 'required', message: COMMENT_REQUIRED } : null));
      validate(p.reason, ({ value }) => (value().length > 500 ? { kind: 'maxLength', message: 'A comment has at most 500 characters.' } : null));
      validate(p.ref, ({ value }) => (this.openDialog() === 'ref' ? (refProblem(value()) ? { kind: 'ref', message: refProblem(value())! } : null) : null));
    },
    { injector: this.injector },
  );
  /** A rule the core reported for a field of the open dialog (422 with a pointer). */
  protected readonly serverErrors = signal<{ readonly [pointer: string]: string }>({});

  protected reasonError(): string | null {
    const server = this.serverErrors()['/reason'];
    if (server) return server;
    if (!this.submitted()) return null;
    return this.rowForm.reason().errors()[0]?.message ?? null;
  }

  protected refError(): string | null {
    const server = this.serverErrors()['/uri'];
    if (server) return server;
    if (!this.submitted()) return null;
    return this.rowForm.ref().errors()[0]?.message ?? null;
  }

  protected readonly rotateLead = rotateLead;
  protected readonly replaceHint = replaceHint;

  private open(row: SecretRow, dialog: GwDialog, which: 'rotate' | 'replace' | 'ref', e: Event): void {
    const s = subjectOf(row, this.ctx());
    this.subject.set(s);
    this.busy.set(false);
    this.submitted.set(false);
    this.dialogError.set(null);
    this.valueError.set(null);
    this.refResult.set(null);
    this.serverErrors.set({});
    this.openDialog.set(which);
    this.model.set({ testBefore: s.testable, reason: '', ref: s.ref ?? '' });
    // The dialog names the row: it opens once that is rendered, so `autofocus` lands in the new content.
    dialog.openAfterRender(e.currentTarget as HTMLElement);
  }

  protected askRotate(row: SecretRow, e: Event): void {
    if (!row.canRotate) return;
    this.open(row, this.rotateDialog(), 'rotate', e);
  }

  protected askReplace(row: SecretRow, e: Event): void {
    this.open(row, this.replaceDialog(), 'replace', e);
  }

  protected askReplaceRef(row: SecretRow, e: Event): void {
    this.open(row, this.refDialog(), 'ref', e);
  }

  /** Rotate at the source (only offered when the connector can): 202, or the core's reason as a toast. */
  protected async confirmRotate(): Promise<void> {
    const s = this.subject();
    if (!s || this.busy()) return;
    this.submitted.set(true);
    if (this.rowForm.reason().invalid()) return;
    this.busy.set(true);
    try {
      await this.data.rotate(s.id, this.reason());
      this.rotateDialog().close('rotated');
      this.toast(rotateStartedToast(s));
    } catch (err) {
      if (isApiError(err) && err.kind === 'conflict') {
        this.rotateDialog().close('');
        this.toast({ tone: 'warning', title: `Can't rotate ${s.name}`, body: err.detail });
      } else {
        this.dialogError.set({ title: `${s.name} wasn't rotated`, body: this.errorText(err) });
      }
    } finally {
      this.busy.set(false);
    }
  }

  /** Save new value: read once from the password field, sent, then gone from the page. */
  protected async saveReplace(e: Event): Promise<void> {
    e.preventDefault();
    const s = this.subject();
    const field = this.valueField()?.nativeElement;
    if (!s || !field || this.busy()) return;
    this.submitted.set(true);
    this.serverErrors.set({});
    this.dialogError.set(null);
    this.valueError.set(field.value ? null : 'Enter the new value.');
    if (this.valueError() || this.rowForm.reason().invalid()) {
      this.focusFirstError();
      return;
    }
    this.busy.set(true);
    try {
      const outcome = await this.data.replace(s.id, field.value, s.testable && this.model().testBefore, this.reason());
      if (outcome.ok) {
        field.value = '';
        this.replaceDialog().close('saved');
        this.toast(replacedToast(s, outcome.result));
        this.announcer.announce(`${s.name} saved, version ${outcome.result.version}`);
      } else {
        // The new version waits; the old one stays in use. The dialog stays open with the value for another try.
        this.dialogError.set(replaceFailedText(s, outcome.result.testMessage));
      }
    } catch (err) {
      this.applyError(err, { '/value': (m) => this.valueError.set(m) });
    } finally {
      this.busy.set(false);
    }
  }

  /** Test reference in the dialog: the result says it under the field (and as a toast, as the kit does). */
  protected async testRef(): Promise<void> {
    const s = this.subject();
    if (!s || this.refBusy()) return;
    this.submitted.set(true);
    this.serverErrors.set({});
    if (this.rowForm.ref().invalid()) {
      this.focusFirstError();
      return;
    }
    this.refBusy.set(true);
    this.refResult.set(null);
    try {
      const t = await this.data.testReference(this.model().ref.trim(), s.store);
      this.refResult.set(refTestLine(t));
      this.toast(refTestToast(s.name, t));
    } catch (err) {
      this.refResult.set({ ok: false, text: this.errorText(err) });
    } finally {
      this.refBusy.set(false);
    }
  }

  protected async saveRef(e: Event): Promise<void> {
    e.preventDefault();
    const s = this.subject();
    if (!s || this.busy()) return;
    this.submitted.set(true);
    this.serverErrors.set({});
    this.dialogError.set(null);
    if (this.rowForm.ref().invalid() || this.rowForm.reason().invalid()) {
      this.focusFirstError();
      return;
    }
    this.busy.set(true);
    try {
      await this.data.setReference(s.id, this.model().ref.trim(), s.store, this.reason());
      this.refDialog().close('saved');
      this.toast({ tone: 'success', title: 'Reference saved', body: "It's read at call time and never copied here." });
    } catch (err) {
      this.applyError(err, {});
    } finally {
      this.busy.set(false);
    }
  }

  /** Test in the row (a reference whose store is connected): busy in place, the result as a toast. */
  protected async testRow(row: SecretRow): Promise<void> {
    if (!row.ref || this.testing().has(row.id)) return;
    this.testing.update((s) => new Set([...s, row.id]));
    this.announcer.announce(`Testing reference ${row.name}`);
    try {
      this.toast(refTestToast(row.name, await this.data.testReference(row.ref, row.store ?? null)));
    } catch (err) {
      this.toast({ tone: 'danger', title: `Reference ${row.name} wasn't tested`, body: this.errorText(err) });
    } finally {
      this.testing.update((s) => {
        const next = new Set(s);
        next.delete(row.id);
        return next;
      });
    }
  }

  /** Whatever was typed into a dialog leaves the page with it; the next opening starts clean. */
  protected onDialogClosed(): void {
    const field = this.valueField()?.nativeElement;
    if (field) field.value = '';
    this.openDialog.set(null);
    this.subject.set(null);
  }

  // ── Filters and sort ──────────────────────────────────────────────────────────────────────────────────────────

  protected onSearch(value: string): void {
    this.filters.update((f) => ({ ...f, q: value }));
  }

  protected onSource(value: string): void {
    this.filters.update((f) => ({ ...f, source: parseSource(value) }));
  }

  protected onSort(change: SortChange): void {
    this.sort.set({ key: change.key === 'rotated' ? 'rotated' : 'name', direction: change.direction });
  }

  /** "Clear filters" (no rows match): everything back to all, the keyboard back in the search (kit). */
  protected clearFilters(): void {
    this.filters.set(NO_FILTERS);
    afterNextRender(() => this.search()?.nativeElement.focus(), { injector: this.injector });
  }

  // ── Errors ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected retry(): void {
    void this.data.refresh();
  }

  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : NO_ANSWER;
  }

  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/secrets → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }

  /** A 422 names its field: the message goes under it; anything else is the dialog's alert. */
  private applyError(err: unknown, own: { readonly [pointer: string]: (message: string) => void }): void {
    if (isApiError(err) && err.kind === 'unprocessable' && err.errors.length) {
      const errors: Record<string, string> = {};
      for (const fe of err.errors) {
        const mine = own[fe.field];
        if (mine) mine(fe.message);
        else errors[fe.field] = fe.message;
      }
      this.serverErrors.set(errors);
      // The core asks for a comment the dialog didn't offer: the owner's env wasn't known here (Connections didn't
      // answer), yet the core knows it is prod. The field appears with the core's words under it (review 2026-10-26:
      // the message went to a field that wasn't on screen, so Save seemed to do nothing).
      const s = this.subject();
      if ('/reason' in errors && s && !s.prod) this.subject.set({ ...s, prod: true });
      const known = err.errors.some((fe) => own[fe.field] || fe.field === '/reason' || fe.field === '/uri' || fe.field === '/value');
      if (!known) this.dialogError.set({ title: err.title, body: err.detail });
      this.focusFirstError();
      return;
    }
    this.dialogError.set({ title: isApiError(err) ? err.title : "It didn't save", body: this.errorText(err) });
  }

  /** Forms rule: after a failed submit the focus goes to the first field with an error. */
  private focusFirstError(): void {
    afterNextRender(() => this.doc.querySelector<HTMLElement>('dialog[open] [aria-invalid="true"]')?.focus(), { injector: this.injector });
  }

  private reason(): string | null {
    const r = this.model().reason.trim();
    return r ? r : null;
  }

  private toast(t: ToastText): void {
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body, timeout: t.timeout });
  }
}
