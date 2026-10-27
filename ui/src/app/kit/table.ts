import { Directive, ElementRef, afterNextRender, inject, output } from '@angular/core';
import { Announcer } from './announcer';

export interface SortChange {
  /** The `data-sort-key` of the column, else its button's label. */
  readonly key: string;
  readonly direction: 'ascending' | 'descending';
}

/**
 * `<tr gwSortRow (sortChange)>` in a `thead` (kit.js "table sort"): a click on a column's `.gw-table__sort` button
 * cycles none → ascending → descending, resets the other `th[aria-sort]` of the row to `none`, and announces
 * "Sorted by Time, descending". The page re-sorts (or re-queries) from `sortChange`.
 */
@Directive({ selector: 'tr[gwSortRow]', host: { '(click)': 'onClick($event)' } })
export class GwSortRow {
  private readonly el: HTMLTableRowElement = inject(ElementRef<HTMLTableRowElement>).nativeElement;
  private readonly announcer = inject(Announcer);
  readonly sortChange = output<SortChange>();

  protected onClick(e: MouseEvent): void {
    const btn = e.target instanceof Element ? e.target.closest<HTMLElement>('th[aria-sort] .gw-table__sort') : null;
    if (!btn || !this.el.contains(btn)) return;
    const th = btn.closest('th') as HTMLTableCellElement;
    const next = th.getAttribute('aria-sort') === 'ascending' ? 'descending' : 'ascending';
    this.el.querySelectorAll('th[aria-sort]').forEach((h) => h.setAttribute('aria-sort', 'none'));
    th.setAttribute('aria-sort', next);
    const label = btn.textContent?.trim() ?? '';
    this.announcer.announce(`Sorted by ${label}, ${next}`);
    this.sortChange.emit({ key: th.getAttribute('data-sort-key') ?? label, direction: next });
  }
}

/**
 * `<table gwSelectable>` (kit.js "select rows"): `input[data-gw-select-all]` in the head checks every
 * `input[data-gw-select-row]`; each row checkbox mirrors into `tr[aria-selected]`; the head checkbox is checked when
 * all are and `indeterminate` when some are. `selectionChange` reports the checked rows' `value`s.
 */
@Directive({ selector: 'table[gwSelectable]', host: { '(change)': 'onChange($event)' } })
export class GwSelectable {
  private readonly el: HTMLTableElement = inject(ElementRef<HTMLTableElement>).nativeElement;
  readonly selectionChange = output<string[]>();

  constructor() {
    afterNextRender(() => this.sync());
  }

  protected onChange(e: Event): void {
    const t = e.target instanceof HTMLInputElement ? e.target : null;
    if (!t) return;
    if (t.matches('[data-gw-select-all]')) {
      this.rows().forEach((c) => {
        c.checked = t.checked;
        c.closest('tr')?.setAttribute('aria-selected', String(t.checked));
      });
      t.indeterminate = false;
      this.emit();
      return;
    }
    if (t.matches('[data-gw-select-row]')) {
      t.closest('tr')?.setAttribute('aria-selected', String(t.checked));
      this.syncHead();
      this.emit();
    }
  }

  /** Re-reads the checkboxes (after the page re-rendered rows). */
  sync(): void {
    this.rows().forEach((c) => c.closest('tr')?.setAttribute('aria-selected', String(c.checked)));
    this.syncHead();
  }

  private syncHead(): void {
    const head = this.el.querySelector<HTMLInputElement>('input[data-gw-select-all]');
    if (!head) return;
    const boxes = this.rows();
    const checked = boxes.filter((c) => c.checked).length;
    head.checked = boxes.length > 0 && checked === boxes.length;
    head.indeterminate = checked > 0 && checked < boxes.length;
  }

  private rows(): HTMLInputElement[] {
    return Array.from(this.el.querySelectorAll<HTMLInputElement>('input[data-gw-select-row]'));
  }

  private emit(): void {
    this.selectionChange.emit(this.rows().filter((c) => c.checked).map((c) => c.value));
  }
}

/** `<input type="checkbox" gwIndeterminate>` starts indeterminate (an HTML property, not an attribute). */
@Directive({ selector: 'input[gwIndeterminate]' })
export class GwIndeterminate {
  constructor() {
    const el: HTMLInputElement = inject(ElementRef<HTMLInputElement>).nativeElement;
    afterNextRender(() => (el.indeterminate = true));
  }
}
