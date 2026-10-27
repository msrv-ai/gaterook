// Ports main can check (U16 "Use another port": findFreePort, usePort). "Free" means main could bind it on
// 127.0.0.1 just now, the address the core listens on; another program may still take it before the core does —
// then the core says PORT_IN_USE again and the screen asks again.
import { createServer } from 'node:net';
import { MIN_USER_PORT } from './desktop-config.ts';

/** Whether a listener on 127.0.0.1:`port` can be opened right now (closed again at once). */
export function canBind(port: number, host = '127.0.0.1'): Promise<boolean> {
  return new Promise((resolve) => {
    const server = createServer();
    server.unref();
    server.once('error', () => resolve(false));
    server.listen({ port, host, exclusive: true }, () => server.close(() => resolve(true)));
  });
}

/** How far `findFreePort` looks before it gives up. */
export const SEARCH_LIMIT = 200;

/**
 * The first port from `from` up that main can bind now, skipping `exclude` (the tunnel's port, never the local
 * one), or null when none of the next {@link SEARCH_LIMIT} is free.
 */
export async function findFreePort(from: number, exclude: readonly number[],
    bind: (port: number) => Promise<boolean> = canBind): Promise<number | null> {
  if (!Number.isInteger(from)) return null;
  const start = Math.max(MIN_USER_PORT, Math.min(from, 65_535));
  for (let port = start; port <= 65_535 && port < start + SEARCH_LIMIT; port++) {
    if (exclude.includes(port)) continue;
    if (await bind(port)) return port;
  }
  return null;
}
