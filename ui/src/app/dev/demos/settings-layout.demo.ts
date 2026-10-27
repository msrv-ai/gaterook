import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GwCopy } from '../../kit/copy';
import { GwIcon } from '../../kit/icons';
import {
  GwSaveBar,
  GwSettingsLayout,
  GwSettingsSection,
  SETTINGS_GROUPS,
} from '../../kit/elements/settings-layout/settings-layout';
import { GwThemeControl } from '../../kit/theme';
import { ToastService } from '../../kit/toast.service';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';

/** Dev only: design/elements/settings-layout.html with the demo world's settings (BLUEPRINT §9). */
@Component({
  selector: 'gw-settings-layout-demo',
  imports: [GwDemoHeader, GwSettingsLayout, GwSettingsSection, GwSaveBar, GwIcon, GwCopy, GwThemeControl, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  templateUrl: './settings-layout.demo.html',
})
export class SettingsLayoutDemo {
  private readonly toasts = inject(ToastService);
  protected readonly el = kitElement('settings-layout');
  protected readonly groups = SETTINGS_GROUPS;
  protected readonly current = signal<string | null>('appearance');

  /** Client registration: saved DCR, switched to CIMD and not saved yet (the kit's demo state). */
  protected readonly savedRegistration = signal('dcr');
  protected readonly registration = signal('cimd');
  protected readonly oauthDirty = computed(() => this.registration() !== this.savedRegistration());

  protected readonly savedApprovers = signal('linked');
  protected readonly approvers = signal('linked');
  protected readonly savedCommentProd = signal(true);
  protected readonly commentProd = signal(true);
  protected readonly rulesDirty = computed(
    () => this.approvers() !== this.savedApprovers() || this.commentProd() !== this.savedCommentProd(),
  );

  protected saveOauth(e: Event): void {
    e.preventDefault();
    this.savedRegistration.set(this.registration());
    this.toasts.show({ title: 'OAuth 2.1 for claude.ai saved', tone: 'success' });
  }

  protected discardOauth(): void {
    this.registration.set(this.savedRegistration());
  }

  protected saveRules(e: Event): void {
    e.preventDefault();
    this.savedApprovers.set(this.approvers());
    this.savedCommentProd.set(this.commentProd());
    this.toasts.show({ title: 'Decisions saved', tone: 'success' });
  }

  protected discardRules(): void {
    this.approvers.set(this.savedApprovers());
    this.commentProd.set(this.savedCommentProd());
  }

  /** Settings that apply at once confirm with a toast. */
  protected applied(label: string, value: string): void {
    this.toasts.show({ title: 'Saved', body: `${label}: ${value}.`, tone: 'success' });
  }
}
