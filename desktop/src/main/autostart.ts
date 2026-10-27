// The Linux autostart entry (~/.config/autostart/mcp-gateway.desktop, login.ts): text only, so it can be tested.

/**
 * One quoted argument of a .desktop `Exec` line (Desktop Entry spec, "The Exec key"): inside double quotes `"`, `` ` ``,
 * `$` and `\` take a backslash, then the value's own escaping doubles every backslash again, and `%` is doubled.
 */
export function desktopExecArg(arg: string): string {
  const quoted = arg.replace(/(["`$\\])/g, '\\$1');
  return `"${quoted.replace(/\\/g, '\\\\').replace(/%/g, '%%')}"`;
}
