import { DEMO_POLICY_SECTIONS, DEMO_TOOL_GROUPS, DEMO_TOOL_GROUPS_EXTERNAL } from '../../../dev/fixtures-tools';
import { bulkHints, inherited, limitFields, matches, modeCounts, modeNote, overrideCount, overridden, toolCount, withUnit } from './tool-policy-view';

const tools = DEMO_TOOL_GROUPS.flatMap((g) => g.group.tools);
const tool = (name: string) => tools.find((t) => t.name === name)!;
const external = (name: string) => DEMO_TOOL_GROUPS_EXTERNAL[0].group.tools.find((t) => t.name === name)!;

describe('tool-policy-list view (design/elements/tool-policy-list.html)', () => {
  it('counts modes in the kit order', () => {
    expect(modeCounts(tools).map((c) => c.text)).toEqual(['6 read only', '3 approval', '5 off']);
    expect(toolCount(14)).toBe('14 tools');
    expect(toolCount(1)).toBe('1 tool');
  });

  it('counts and filters bypass tools without classifying them as read only', () => {
    const bypass = { ...tool('sql_execute'), mode: 'BYPASS' as const };
    expect(modeCounts([bypass])).toEqual([{ mode: 'BYPASS', count: 1, text: '1 bypass' }]);
    expect(matches(bypass, null, '', 'BYPASS')).toBe(true);
    expect(matches(bypass, null, '', 'READ_ONLY')).toBe(false);
    expect(modeNote(bypass, { slug: 'dev', readOnly: false })).toMatchObject({
      icon: 'shield-off', readOnlyDisabled: true, text: 'Bypass runs without approval, including changes to data. Policy limits still apply.',
    });
  });

  it('filters by name, description and mode', () => {
    expect(matches(tool('sql_query'), 'Run one SELECT', 'select', 'ALL')).toBe(true);
    expect(matches(tool('sql_query'), null, 'sql', 'APPROVAL')).toBe(false);
    expect(matches(tool('sql_execute'), null, '', 'APPROVAL')).toBe(true);
  });

  it('a tool that changes data cannot use Read only', () => {
    expect(modeNote(tool('sql_execute'), { slug: 'dev', readOnly: false })).toMatchObject({ text: 'Mutates data: approval, bypass or off', readOnlyDisabled: true });
    expect(modeNote(tool('sql_execute'), { slug: 'incident-prod', readOnly: true })).toMatchObject({
      text: 'Locked: endpoint incident-prod is read only, so tools that change data stay off',
      locked: true,
    });
    expect(modeNote(tool('sql_query'), { slug: 'dev', readOnly: false })).toBeNull();
    expect(modeNote(tool('ssh_run'), { slug: 'dev', readOnly: false })?.text).toBe('Read only runs runbooks marked read only');
  });

  it('external tools: read only only after a confirmation, never for declared writers', () => {
    expect(modeNote(external('list_issues'), { slug: 'dev', readOnly: false })).toMatchObject({ needsConfirmation: true });
    expect(modeNote(external('merge_pull_request'), { slug: 'dev', readOnly: false })?.text).toBe('Declared destructive: approval, bypass or off');
    expect(modeNote(external('create_issue'), { slug: 'dev', readOnly: false })?.text).toBe('Not declared read only: approval, bypass or off');
  });

  it('says what "Set all to…" does to each group', () => {
    const pg = DEMO_TOOL_GROUPS[0].group.tools;
    expect(bulkHints('dev-db', pg, { readOnly: false })).toEqual({
      off: "Agents can't call any dev-db tool",
      read: 'sql_execute stays as it is: it changes data',
      approval: 'Every call waits for your decision',
      bypass: 'Calls run without approval, including changes to data; policy limits still apply',
    });
    expect(bulkHints('prod-replica', pg, { readOnly: true }).read).toBe('sql_execute stays off: the endpoint is read only');
  });

  it('inherits limits from the policy and keeps only stricter overrides', () => {
    const fields = limitFields('postgres');
    expect(fields.map((f) => f.key)).toEqual(['rowLimit', 'statementTimeoutMs', 'maxResponseKb']);
    expect(inherited(fields[1], DEMO_POLICY_SECTIONS.dev)).toBe(5);
    expect(inherited(fields[0], DEMO_POLICY_SECTIONS.prod)).toBe(100);
    expect(overridden(fields[0], { rowLimit: 200 })).toBe(200);
    expect(overrideCount(fields, { rowLimit: 200 })).toBe(1);
    expect(withUnit(1000, 'rows')).toBe('1,000 rows');
    expect(limitFields('ssh').map((f) => f.key)).toEqual(['commandTimeoutSeconds', 'maxOutputKb']);
    expect(limitFields('ssh-logs').map((f) => [f.section, f.key])).toEqual([
      ['limits', 'logLines'], ['limits', 'toolTimeoutSeconds'], ['limits', 'maxResponseKb'], ['ssh', 'maxOutputKb'],
    ]);
    for (const type of ['gcp-logging', 'cloudwatch', 'loki']) {
      expect(limitFields(type).map((f) => f.key)).toEqual(['logLines', 'toolTimeoutSeconds', 'maxResponseKb']);
    }
    expect(limitFields('opensearch').map((f) => f.key)).toEqual(['toolTimeoutSeconds', 'maxResponseKb']);
  });
});
