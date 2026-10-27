/** The kit's 17 elements (design/elements/*.html): the gallery's index and each demo's header. */
export interface KitElement {
  readonly name: string;
  readonly title: string;
  readonly description: string;
}

export const KIT_ELEMENTS: readonly KitElement[] = [
  {
    name: 'setup-map',
    title: 'Setup map',
    description:
      "What to configure and how it connects, in the order you set it up: secrets and resources, connections, policies, endpoints, AI apps (the order of Getting started and onboarding). An agent's call travels the same path the other way, and the secret is injected at the connection, never reaching the agent. Each node says its state in words and links to its page. A chain on Overview and in onboarding, wiring on endpoint detail.",
  },
  {
    name: 'how-it-works',
    title: 'How it works',
    description:
      'Three or four numbered steps that explain a mechanism before the user configures it: what happens to a secret, how an AI app is authorized, what one-click Connect writes. It opens Secrets, AI apps and Connectors and the Authorization, Notifications and Tunnel settings, and folds away once the user knows it.',
  },
  {
    name: 'getting-started',
    title: 'Getting started',
    description:
      'The first-run checklist on Overview and at the end of onboarding: six steps in order, how far the user has got, and the one thing to do next. Done steps fold to a line; once all six are done it shrinks to a calm summary until dismissed.',
  },
  {
    name: 'approval-request',
    title: 'Approval request',
    description:
      "The decision UI for one mutation an agent asked for: the exact action, verbatim, with why it needs a human, its impact and the agent's unverified note, then Deny or Approve and run. Used in the Approvals detail pane, in Overview as a compact row, and read-only in the Activity drawer.",
  },
  {
    name: 'approval-notification',
    title: 'Approval notification',
    description:
      'How one approval request reaches the approver outside the window: the desktop notification, Slack, Telegram and the in-app toast, with the same fields as the request. Used in Approvals (delivery channels) and in Settings · Notifications.',
  },
  {
    name: 'tool-policy-list',
    title: 'Tool policy list',
    description:
      'What agents may call on one endpoint, and how: every tool grouped by its connection, with its MCP annotations, the mode picker Off · Read only · Approval and per-tool limits over the environment policy. There is no mode that lets an agent change data without you. Used on endpoint detail (Tools & policies), Policies and Playground.',
  },
  {
    name: 'risk-callout',
    title: 'Risk callout',
    description:
      'Warns when one endpoint mixes private data, untrusted content and a way to send data out: an agent that reads untrusted text can be steered into leaking private data. Database rows, logs and HTTP responses count as untrusted; every HTTP and SSH tool counts as a channel (fixed runbooks as a limited one). Used on endpoint detail, Endpoints (compact) and Policies.',
  },
  {
    name: 'token-issue',
    title: 'Token issue',
    description:
      'Access tokens of one endpoint: issue a token, show it once, then list it only by its prefix with its lifetime, last use and a revoke that names it. Used on endpoint detail (Tokens) and Settings › Authorization.',
  },
  {
    name: 'client-card',
    title: 'Client card',
    description:
      'One MCP client: is it installed, which endpoints it uses, how it connects and authenticates, and the next step. Connect never writes blind: it shows the exact change to the config file and the backup path first. Claude Desktop (one bridge extension per endpoint) and Codex (a headers helper) get 15-minute tokens on the fly and no token is written for them; Cursor and Antigravity keep a token in their config, sign in through the browser or go through the bridge, and a prod endpoint offers them only the bridge. claude.ai comes in through a published tunnel, behind three gates. Used on AI apps, on the AI apps tab of an endpoint (compact rows) and in onboarding.',
  },
  {
    name: 'connection-form',
    title: 'Connection form',
    description:
      "The Configure step of Add connection, shown for PostgreSQL. Fields come from the connector's JSON Schema; the gateway adds the secret source, the environment with its safety defaults and a Test connection panel that shows what the database user may do before anything is saved.",
  },
  {
    name: 'connector-card',
    title: 'Connector card',
    description:
      'One connector type: what it connects to, where it comes from, whether the catalog signed it, how many tools it brings and the one thing to do next. Catalog cards fill the grid on Connectors; radio cards grouped by category are step 1 of Add connection.',
  },
  {
    name: 'event-detail',
    title: 'Event detail',
    description:
      'Everything about one logged call, in the order you ask about it: what happened, who asked, why the policy decided so, what came back and where the time went. Opens in a drawer from an Activity row; also the history detail in Approvals and the last call in Playground.',
  },
  {
    name: 'log-explorer',
    title: 'Log explorer',
    description:
      'The query and filter zone above the events table in Activity, reused as the filter bar of Insights: a structured query, a time range, facets with counts and an hourly histogram by decision. Every filter is a removable tag; every bar zooms into its hour.',
  },
  {
    name: 'settings-layout',
    title: 'Settings layout',
    description:
      'Settings split into the groups people look for: Workspace, Security, Approvals, Network and Data. A settings nav on the left stays in view and marks where you are; each item is one section of topics on the right, with its own Save bar only where values need saving. Used on Settings and on the Settings tab of an endpoint.',
  },
  {
    name: 'status-screen',
    title: 'Status screen',
    description:
      'What the window shows while the gateway core is not available: starting, failed to start (port in use, master key missing, any other reason), or stopped. It replaces the whole app (no sidebar, nothing to navigate to), says what is happening and why, and offers the one or two actions that fix it. Used on Core starting and by the desktop app whenever the core exits. Each state below sits in a preview frame; in the app the screen covers the window.',
  },
  {
    name: 'command-palette',
    title: 'Command palette',
    description:
      'One keyboard entry point to the whole app: go to a page, run an action, or open a connection or an endpoint by name without leaving the keyboard. Opens from every page with ⌘K (Ctrl+K on Windows and Linux) or the Search button in the titlebar.',
  },
  {
    name: 'status-tile',
    title: 'Status tile',
    description:
      'One compact card per thing: what it is, its state in words, and at most one action. Used on Overview for connections and AI apps, in gw-grid --3 sets (--2 for the four AI apps), problems and tiles with an action first.',
  },
];

export function kitElement(name: string): KitElement {
  const e = KIT_ELEMENTS.find((x) => x.name === name);
  if (!e) throw new Error(`unknown kit element ${name}`);
  return e;
}
