/** Fixed client destinations: the renderer cannot pass an executable, path or arbitrary URL. */
export function aiPromptUrl(client: unknown, prompt: unknown): string {
  if (typeof prompt !== 'string' || !prompt.trim() || prompt.length > 8000 || prompt.includes('\0')) {
    throw new Error('Enter a prompt of up to 8,000 characters.');
  }
  const destinations: Record<string, readonly [string, string]> = {
    claude: ['claude://claude.ai/new', 'q'],
    codex: ['codex://threads/new', 'prompt'],
    cursor: ['cursor://anysphere.cursor-deeplink/prompt', 'text'],
  };
  if (typeof client !== 'string' || !Object.hasOwn(destinations, client)) throw new Error('Choose Claude Desktop, Codex or Cursor.');
  const [base, key] = destinations[client]!;
  const encoded = encodeURIComponent(prompt);
  // Cursor's URI layer decodes the query before its deeplink extension parses and decodes the text parameter.
  // Protect literal +, %, and parameter-like text from that second pass (verified with Cursor 3.27.22).
  const value = client === 'cursor' ? encodeURIComponent(encoded) : encoded;
  const url = `${base}?${key}=${value}`;
  if (url.length > 10000) throw new Error('This prompt is too long for an app link. Use Copy prompt instead.');
  return url;
}
