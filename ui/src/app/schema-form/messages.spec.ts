import { CONNECTOR_FORMS } from '../../testing/connector-schemas';
import { JsonObject } from '../core/api/types';
import { compileSchema } from './compile';
import { valueProblem } from './messages';

describe('schema-form validation for metadata choices', () => {
  it('accepts a Kubernetes context before discovery and a saved context absent from current metadata', () => {
    const context = compileSchema(CONNECTOR_FORMS['kubernetes']).all.find((node) => node.pointer === '/context')!;
    expect(context.optionsFrom).toEqual(['kubeconfigContexts']);
    expect(valueProblem(context, 'dev-cluster')).toBeNull();
    expect(valueProblem(context, 'previous-context')).toBeNull();
  });

  it('retains string validation for fields whose choices come from metadata', () => {
    const schema: JsonObject = {
      type: 'object', properties: {
        context: { type: 'string', title: 'Context', pattern: '^[a-z-]+$', maxLength: 12, 'x-options-from': 'metadata.contexts' },
      },
    };
    const context = compileSchema(schema).all.find((node) => node.pointer === '/context')!;
    expect(valueProblem(context, 'dev-cluster')).toBeNull();
    expect(valueProblem(context, 'INVALID')).toContain('not a valid context');
    expect(valueProblem(context, 'a-context-name-that-is-too-long')).toContain('the limit is 12');
  });

  it('continues to reject values outside an explicit schema enum', () => {
    const schema: JsonObject = {
      type: 'object', properties: {
        context: { type: 'string', enum: ['dev', 'stage'], 'x-options-from': 'metadata.contexts' },
      },
    };
    const context = compileSchema(schema).all.find((node) => node.pointer === '/context')!;
    expect(valueProblem(context, 'dev')).toBeNull();
    expect(valueProblem(context, 'prod')).toBe('Choose one of the listed options.');
    const auth = compileSchema(CONNECTOR_FORMS['kubernetes']).all.find((node) => node.pointer === '/auth')!;
    expect(valueProblem(auth, 'unrecognized')).toBe('Choose one of the listed options.');
  });
});

describe('schema-form validation for resource scopes', () => {
  it('explains how to correct a bare GCP project ID and accepts each supported resource format', () => {
    const scopes = compileSchema(CONNECTOR_FORMS['gcp']).all.find((node) => node.pointer === '/scopes')!;
    expect(valueProblem(scopes, ['acme-stage'])).toBe(
      'acme-stage is not a valid resource scope. Use a value such as projects/acme-stage.',
    );
    expect(valueProblem(scopes, ['projects/acme-stage', 'projects/123456789012', 'folders/123456789012', 'organizations/123456789012'])).toBeNull();
  });

  it('uses only a string example that matches the item pattern', () => {
    const schema: JsonObject = {
      type: 'object', properties: {
        scopes: { type: 'array', items: { type: 'string', title: 'resource scope', pattern: '^projects/[a-z-]+$',
          examples: [123, 'acme-stage', 'projects/acme-stage'] } },
      },
    };
    const scopes = compileSchema(schema).all[0];
    expect(valueProblem(scopes, ['acme-stage'])).toBe(
      'acme-stage is not a valid resource scope. Use a value such as projects/acme-stage.',
    );
  });

  it('keeps validation working for schemas without item examples', () => {
    const schema: JsonObject = {
      type: 'object', properties: {
        scopes: { type: 'array', items: { type: 'string', title: 'resource scope', pattern: '^projects/[a-z-]+$' } },
      },
    };
    expect(valueProblem(compileSchema(schema).all[0], ['acme-stage'])).toBe('acme-stage is not a valid resource scope.');
  });
});
