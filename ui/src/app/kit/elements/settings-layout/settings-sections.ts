// The settings sections on their own, so the shell's deep-link allowlist can import them without the layout component.

/** One item of the settings nav: a section of the page (`#settings-<id>`, route `<base>/<id>`). */
export interface SettingsNavItem {
  readonly id: string;
  readonly label: string;
  readonly icon: string;
}

export interface SettingsNavGroup {
  readonly label: string;
  readonly items: readonly SettingsNavItem[];
}

/** Settings › the groups people look for (BLUEPRINT §2, elements/settings-layout.html). */
export const SETTINGS_GROUPS: readonly SettingsNavGroup[] = [
  {
    label: 'Workspace',
    items: [
      { id: 'general', label: 'General', icon: 'sliders-horizontal' },
      { id: 'appearance', label: 'Appearance', icon: 'sun-moon' },
    ],
  },
  {
    label: 'Security',
    items: [
      { id: 'authorization', label: 'Authorization', icon: 'shield-check' },
      { id: 'users', label: 'Users & roles', icon: 'users' },
    ],
  },
  {
    label: 'Approvals',
    items: [
      { id: 'notifications', label: 'Notifications', icon: 'bell' },
      { id: 'approval-rules', label: 'Approval rules', icon: 'list-checks' },
    ],
  },
  {
    label: 'Network',
    items: [
      { id: 'local-api', label: 'Local API', icon: 'server' },
    ],
  },
  {
    label: 'Data',
    items: [
      { id: 'audit', label: 'Audit & retention', icon: 'scroll-text' },
      { id: 'backup', label: 'Backup', icon: 'archive' },
    ],
  },
];
