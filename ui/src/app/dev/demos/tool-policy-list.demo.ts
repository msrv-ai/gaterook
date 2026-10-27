import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointsApi } from '../../core/api/endpoints.api';
import { PoliciesApi } from '../../core/api/policies.api';
import { Env, JsonObject } from '../../core/api/types';
import { GwToolPolicyList } from '../../kit/elements/tool-policy-list/tool-policy-list';
import { ToolPolicyGroup, toolPolicyGroups } from '../../kit/elements/tool-policy-list/tool-policy-view';
import { ToastService } from '../../kit/toast.service';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_POLICY_SECTIONS, DEMO_TOOL_DESCRIPTIONS, DEMO_TOOL_GROUPS_EXTERNAL, DEMO_TOOL_GROUPS_PROD } from '../fixtures-tools';
import { GwLiveNote, liveRead } from '../live';

/** Everything the dev endpoint's sections need, from the dev core. */
interface LiveDev {
  readonly groups: ToolPolicyGroup[];
  readonly descriptions: Record<string, string>;
  readonly policies: Partial<Record<Env, JsonObject>>;
}

/**
 * Dev only: design/elements/tool-policy-list.html. Endpoint dev is the dev core's: its tools
 * (`GET /api/endpoints/dev/tools`), the connector types' tool descriptions and the effective policies
 * (`GET /api/policies`). The read-only prod endpoint and the external MCP server's tools are fixtures: the demo
 * world's incident-prod isn't read only and external MCP servers come with K4.
 */
@Component({
  selector: 'gw-tool-policy-list-demo',
  imports: [GwDemoHeader, GwToolPolicyList, GwLiveNote],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="tpl-a-title">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="tpl-a-title">Endpoint dev: all tools</h2>
        <p class="gw-section__description">Default state: the 14 tools of five of the eight dev connections (orders-mongo, cache-redis and aws-stage are collapsed out of the demo), grouped by the connection they run on. Destructive tools offer only Off or Approval, and the reason sits under the picker. The limits of <code class="gw-code">sql_query</code> are open: the row limit overrides the dev policy, the other two are inherited. <code class="gw-code">http_request</code> has one override while closed. “Set all to…” changes a whole connection.</p>
      </header>
      @if (dev.value(); as d) {
      <div gwToolPolicyList [groups]="d.groups" slug="dev" [descriptions]="d.descriptions" [policies]="d.policies" [openLimits]="['dev-db/sql_query']" (modeChange)="say('Mode of ' + $event.toolKey + ' → ' + $event.mode)" (bulk)="say('Set all ' + $event.connection + ' tools → ' + $event.mode)" (overridesChange)="say('Limits of ' + $event.toolKey + ' saved')"></div>
      } @else {
        <p [gwLiveNote]="dev"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="tpl-b-title">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="tpl-b-title">Filtered by mode</h2>
        <p class="gw-section__description">Mode: Approval keeps only the tools that wait for a person. The summary says how many of the 14 match, and each group says how many of its tools are shown; groups with no match are left out.</p>
      </header>
      @if (dev.value(); as d) {
        <div gwToolPolicyList [groups]="d.groups" slug="dev" [descriptions]="d.descriptions" [policies]="d.policies" modeFilter="APPROVAL"></div>
      } @else {
        <p [gwLiveNote]="dev"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="tpl-c-title">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="tpl-c-title">Locked by the endpoint</h2>
        <p class="gw-section__description">Endpoint <code class="gw-code">incident-prod</code> is read only. The whole picker of <code class="gw-code">sql_execute</code> is disabled and the note under it names the reason. Both connections are prod, so the inherited limits come from the stricter prod policy.</p>
      </header>
      <div gwToolPolicyList [groups]="prodGroups" slug="incident-prod" endpointEnv="prod" readOnly [descriptions]="descriptions" [policies]="policies"></div>
    </section>

    <section class="gw-section" aria-labelledby="tpl-f-title">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="tpl-f-title">Tools of an external MCP server</h2>
        <p class="gw-section__description">The 26 tools of <code class="gw-code">github-mcp</code> just added to endpoint <code class="gw-code">dev</code> (4 shown). Their annotations come from the server, not from the gateway, so each row says “Declared by github-mcp, not verified” next to them. They start in Approval. A tool the server declares read only can go to Read only, but only after you confirm it for that one tool: the option is unavailable with its reason and a Confirm button beside it. A tool not declared read only, or declared destructive, stays approval or off (native <code class="gw-code">disabled</code>: the option would remove the approval). The same holds for plugin tools.</p>
      </header>
      <div gwToolPolicyList [groups]="externalGroups" slug="dev" noun="new tools" [showModeFilter]="false" [descriptions]="descriptions" [policies]="policies" (confirmReadOnly)="say($event.toolKey + ' is read only on dev')"></div>
    </section>

    <section class="gw-section" aria-labelledby="tpl-d-title">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="tpl-d-title">No tools match</h2>
        <p class="gw-section__description">The search found nothing: the summary says so, the groups give way to one empty state, and one action clears the search.</p>
      </header>
      @if (dev.value(); as d) {
        <div gwToolPolicyList [groups]="d.groups" slug="dev" [descriptions]="d.descriptions" [policies]="d.policies" query="s3"></div>
      } @else {
        <p [gwLiveNote]="dev"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="tpl-e-title">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="tpl-e-title">Tool picker in Playground</h2>
        <p class="gw-section__description">Same groups, annotations and environment markers; the mode is read only here (<code class="gw-code">gw-mode</code>) and each row opens the tool in the playground. The current tool, <code class="gw-code">sql_query</code>, is the selected row. Two of the five groups are shown.</p>
      </header>
      @if (dev.value(); as d) {
        <div gwToolPolicyList variant="picker" [groups]="picker(d.groups)" slug="dev" [descriptions]="d.descriptions" selected="sql_query" pickerRoute="/dev/elements/tool-policy-list"></div>
      } @else {
        <p [gwLiveNote]="dev"></p>
      }
    </section>
  `,
})
export class ToolPolicyListDemo {
  private readonly toasts = inject(ToastService);
  private readonly endpoints = inject(EndpointsApi);
  private readonly types = inject(ConnectorTypesApi);
  private readonly policyApi = inject(PoliciesApi);
  protected readonly el = kitElement('tool-policy-list');
  protected readonly prodGroups = DEMO_TOOL_GROUPS_PROD;
  protected readonly externalGroups = DEMO_TOOL_GROUPS_EXTERNAL;
  protected readonly descriptions = DEMO_TOOL_DESCRIPTIONS;
  protected readonly policies = DEMO_POLICY_SECTIONS;
  protected readonly dev = liveRead(() => this.load('dev'));

  /** As endpoint-detail does: tools, the endpoint's connections, the types' tool descriptions, the policies. */
  private async load(slug: string): Promise<LiveDev> {
    const [tools, detail, policies] = await Promise.all([this.endpoints.tools(slug), this.endpoints.get(slug), this.policyApi.all()]);
    const types = [...new Set(detail.connections.map((c) => c.type))];
    const details = await Promise.all(types.map((t) => this.types.get(t)));
    const descriptions: Record<string, string> = {};
    for (const d of details) for (const t of d.tools) descriptions[t.name] = t.description;
    return {
      groups: toolPolicyGroups(tools, detail.connections),
      descriptions,
      policies: { dev: policies.dev.sections, stage: policies.stage.sections, prod: policies.prod.sections },
    };
  }

  /** The picker shows two of the groups, as the kit does. */
  protected picker(groups: readonly ToolPolicyGroup[]): ToolPolicyGroup[] {
    return groups.filter((g) => g.group.connection === 'dev-db' || g.group.connection === 'app-logs');
  }

  protected say(text: string): void {
    this.toasts.show({ tone: 'info', title: 'Demo', body: `${text} (the page sends it to the core).` });
  }
}
