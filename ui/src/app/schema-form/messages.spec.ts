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
