import {
  ParamDraft,
  formatSchema,
  fromDefinition,
  humanDuration,
  inputSchemaOf,
  newParam,
  scanSql,
  scanStatus,
  syncParams,
  toDefinition,
  typedDefault,
} from './template-sql';

/** The kit's template: Templates › "Edit template orders_by_status". */
const ORDERS_SQL = `SELECT id, customer_id, status, total, created_at
FROM orders
WHERE status = :status
  AND created_at >= :since
ORDER BY created_at DESC
LIMIT 100`;

const STATUS: ParamDraft = { name: 'status', type: 'enum', required: true, values: 'pending, paid, shipped, refunded', dflt: '', description: 'Order status' };
const SINCE: ParamDraft = { name: 'since', type: 'timestamp', required: false, values: '', dflt: '-PT24H', description: 'Created at or after' };

describe('template SQL: scan', () => {
  it('finds the kit query’s placeholders and reads it as a read', () => {
    const scan = scanSql(ORDERS_SQL);
    expect(scan).toEqual({ placeholders: ['status', 'since'], statement: 'SELECT', changes: false, error: null });
    expect(scanStatus(scan, ORDERS_SQL)).toEqual({
      tone: 'success',
      text: 'Parsed as SELECT, read only · 2 parameters: ',
      params: [':status', ':since'],
    });
  });

  it('skips literals, quoted names, comments and dollar quotes; casts are not placeholders', () => {
    const sql = [
      "SELECT ':not_a_param', \"col:x\", `t:y`, $tag$ :inside $tag$, $$ :also $$",
      '-- :commented',
      '/* outer /* :nested */ still :comment */',
      'FROM t WHERE a = :a AND b::text = :b AND c := 1 AND d = :a',
    ].join('\n');
    const scan = scanSql(sql);
    expect(scan.placeholders).toEqual(['a', 'b']);
    expect(scan.error).toBeNull();
    // A name right after a word character is not a placeholder (`x:y` inside an identifier-like run).
    expect(scanSql('SELECT a:b FROM t').placeholders).toEqual([]);
    // A doubled quote inside a literal is the quote itself.
    expect(scanSql("SELECT 'it''s :fine' WHERE x = :x").placeholders).toEqual(['x']);
  });

  it('marks what would change data or lock rows', () => {
    expect(scanSql('UPDATE orders SET status = :s').changes).toBe(true);
    expect(scanSql('WITH gone AS (DELETE FROM t RETURNING *) SELECT * FROM gone').changes).toBe(true);
    expect(scanSql('SELECT * INTO backup FROM t').changes).toBe(true);
    expect(scanSql('SELECT * FROM t FOR UPDATE').changes).toBe(true);
    expect(scanSql('SELECT * FROM t FOR NO KEY SHARE').changes).toBe(true);
    expect(scanSql('EXPLAIN ANALYZE SELECT 1').changes).toBe(true);
    expect(scanSql('EXPLAIN SELECT 1').changes).toBe(false);
    expect(scanSql("SELECT 'DELETE' AS word").changes).toBe(false);
    expect(scanSql('-- DELETE in a comment\nSELECT 1').changes).toBe(false);
    const update = scanSql('UPDATE orders SET status = :s');
    expect(scanStatus(update, 'x')).toMatchObject({ tone: 'warning', text: 'Parsed as UPDATE, needs approval · 1 parameter: ' });
  });

  it('refuses a second statement and an unclosed quote or comment', () => {
    const two = scanSql('SELECT 1; DELETE FROM t');
    expect(two.error).toBe('A template runs one statement. Remove the second one.');
    expect(two.changes).toBe(true);
    expect(scanSql('SELECT 1;  \n').error).toBeNull();
    expect(scanSql("SELECT 'open").error).toBe('A quote or comment in the SQL isn’t closed.');
    expect(scanSql('SELECT 1 /* open').error).toBe('A quote or comment in the SQL isn’t closed.');
    expect(scanSql('SELECT $q$ open').error).toBe('A quote or comment in the SQL isn’t closed.');
    expect(scanStatus(scanSql("SELECT 'open"), "SELECT 'open")?.tone).toBe('danger');
    expect(scanStatus(scanSql(''), '  ')).toBeNull();
    expect(scanStatus(scanSql('SELECT 1'), 'SELECT 1')?.text).toBe('Parsed as SELECT, read only · no parameters');
  });
});

describe('template SQL: parameters and definition', () => {
  it('keeps typed rows for names that stay and adds new ones as required strings', () => {
    const rows = syncParams(['since', 'limit'], [STATUS, SINCE]);
    expect(rows).toEqual([SINCE, newParam('limit')]);
    expect(newParam('limit')).toEqual({ name: 'limit', type: 'string', required: true, values: '', dflt: '', description: '' });
  });

  it('types defaults by their parameter', () => {
    expect(typedDefault({ type: 'integer', dflt: ' 100 ' })).toBe(100);
    expect(typedDefault({ type: 'integer', dflt: '1.5' })).toBe('1.5');
    expect(typedDefault({ type: 'number', dflt: '2.5e3' })).toBe(2500);
    expect(typedDefault({ type: 'boolean', dflt: 'false' })).toBe(false);
    expect(typedDefault({ type: 'timestamp', dflt: '-PT24H' })).toBe('-PT24H');
    expect(typedDefault({ type: 'string', dflt: '  ' })).toBeUndefined();
  });

  it('builds the sql-query definition and reads it back', () => {
    const def = toDefinition({ name: ' orders_by_status ', description: 'Recent orders by status.', sql: ORDERS_SQL, params: [STATUS, SINCE] });
    expect(def).toEqual({
      name: 'orders_by_status',
      sql: ORDERS_SQL,
      description: 'Recent orders by status.',
      params: [
        { name: 'status', type: 'enum', required: true, values: ['pending', 'paid', 'shipped', 'refunded'], description: 'Order status' },
        { name: 'since', type: 'timestamp', required: false, default: '-PT24H', description: 'Created at or after' },
      ],
    });
    expect(fromDefinition(def)).toEqual({
      name: 'orders_by_status',
      description: 'Recent orders by status.',
      sql: ORDERS_SQL,
      params: [
        { ...STATUS, values: 'pending, paid, shipped, refunded' },
        { ...SINCE, required: false },
      ],
    });
    // Unknown types read as strings; rows without a name are dropped; required defaults to true.
    expect(fromDefinition({ name: 'x', sql: 'SELECT :a', params: [{ name: 'a', type: 'uuid' }, { type: 'string' }] }).params).toEqual([
      { name: 'a', type: 'string', required: true, values: '', dflt: '', description: '' },
    ]);
    expect(fromDefinition({}).params).toEqual([]);
  });
});

describe('template SQL: what the agent sees', () => {
  it('words durations as the connector does', () => {
    expect(humanDuration('-PT24H')).toBe('24 hours ago');
    expect(humanDuration('-PT1H')).toBe('1 hour ago');
    expect(humanDuration('P2D')).toBe('2 days from now');
    expect(humanDuration('-PT90M')).toBe('90 minutes ago');
    expect(humanDuration('P')).toBeNull();
    expect(humanDuration('yesterday')).toBeNull();
  });

  it('publishes the kit’s input schema for orders_by_status', () => {
    const schema = inputSchemaOf([STATUS, SINCE]);
    expect(schema).toEqual({
      type: 'object',
      properties: {
        status: { type: 'string', enum: ['pending', 'paid', 'shipped', 'refunded'], description: 'Order status' },
        since: { type: 'string', format: 'date-time', description: 'Created at or after. Default: 24 hours ago' },
      },
      required: ['status'],
    });
    expect(formatSchema(schema)).toBe(
      [
        '{',
        '  "type": "object",',
        '  "properties": {',
        '    "status": {',
        '      "type": "string",',
        '      "enum": ["pending", "paid", "shipped", "refunded"],',
        '      "description": "Order status"',
        '    },',
        '    "since": {',
        '      "type": "string",',
        '      "format": "date-time",',
        '      "description": "Created at or after. Default: 24 hours ago"',
        '    }',
        '  },',
        '  "required": ["status"]',
        '}',
      ].join('\n'),
    );
  });

  it('caps strings, keeps plain defaults and drops required when a default fills it', () => {
    const schema = inputSchemaOf([
      { name: 'q', type: 'string', required: true, values: '', dflt: '', description: '' },
      { name: 'limit', type: 'integer', required: true, values: '', dflt: '50', description: 'Rows.' },
      { name: 'day', type: 'date', required: true, values: '', dflt: '2026-10-01', description: '' },
    ]);
    expect(schema).toEqual({
      type: 'object',
      properties: {
        q: { type: 'string', maxLength: 256 },
        limit: { type: 'integer', description: 'Rows. Default: 50', default: 50 },
        day: { type: 'string', format: 'date', description: 'Default: 2026-10-01', default: '2026-10-01' },
      },
      required: ['q'],
    });
    expect(inputSchemaOf([])).toEqual({ type: 'object', properties: {} });
    expect(formatSchema({})).toBe('{}');
    expect(formatSchema([{ a: 1 }])).toBe('[\n  {\n    "a": 1\n  }\n]');
  });
});
