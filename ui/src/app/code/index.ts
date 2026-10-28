// Exact actions and code (ARCHITECTURE.md §8.5): server segments with optional lossless JSON layout. The editor is exported
// on its own path (./code-editor) so that pages can @defer it without pulling CodeMirror into the initial bundle.
export * from './code-block';
export * from './code-lines';
export * from './diff';
export * from './highlight';
export * from './unicode';
