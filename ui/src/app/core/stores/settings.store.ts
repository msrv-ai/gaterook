import { Injectable, inject, signal } from '@angular/core';
import { SettingsApi, SettingsSection, SettingsSectionName } from '../api/settings.api';

/**
 * Settings sections the shell and several pages read (General: single-key shortcuts, updates; Appearance mirrors the
 * kit's theme controls, which apply at once in the renderer). Pages edit sections through SettingsApi and put the
 * saved section back here.
 */
@Injectable({ providedIn: 'root' })
export class SettingsStore {
  private readonly api = inject(SettingsApi);
  private readonly sectionsSignal = signal<{ readonly [name: string]: SettingsSection }>({});

  readonly sections = this.sectionsSignal.asReadonly();

  async load(): Promise<void> {
    try {
      this.sectionsSignal.set(await this.api.all());
    } catch {
      // Not fatal: pages load their own section when they need it.
    }
  }

  section(name: SettingsSectionName): SettingsSection | null {
    return this.sectionsSignal()[name] ?? null;
  }

  put(section: SettingsSection): void {
    this.sectionsSignal.update((all) => ({ ...all, [section.name]: section }));
  }
}
