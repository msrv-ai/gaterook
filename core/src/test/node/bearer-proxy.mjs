// A loopback proxy that adds "Authorization: Bearer <token>" to every request (ARCHITECTURE.md M1.5): conformance
// 0.1.16 has no flag for HTTP headers, and the core has no "no token" switch. Requests addressed to the proxy itself
// (Host/Origin of the proxy) are re-addressed to the core; any other Host or Origin goes through as sent, so the
// core's own DNS-rebinding checks still answer. Prints {"port": n} once it listens.
import http from 'node:http';

const target = new URL(process.env.MCP_TARGET);
const token = process.env.MCP_TOKEN;

const server = http.createServer((req, res) => {
  const port = server.address().port;
  const own = new Set([`127.0.0.1:${port}`, `localhost:${port}`]);
  const headers = { ...req.headers, authorization: `Bearer ${token}` };
  if (own.has(headers.host)) {
    headers.host = target.host;
  }
  if (headers.origin && [...own].some((h) => headers.origin === `http://${h}`)) {
    headers.origin = target.origin;
  }
  const upstream = http.request({ host: target.hostname, port: target.port, method: req.method, path: req.url, headers },
    (r) => {
      res.writeHead(r.statusCode, r.headers);
      r.pipe(res);
    });
  upstream.on('error', (e) => {
    res.writeHead(502, { 'content-type': 'text/plain' });
    res.end(String(e));
  });
  req.pipe(upstream);
});
server.listen(0, '127.0.0.1', () => process.stdout.write(JSON.stringify({ port: server.address().port }) + '\n'));
